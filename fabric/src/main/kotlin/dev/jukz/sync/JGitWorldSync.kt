package dev.jukz.sync

import dev.jukz.JukzMod
import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import dev.jukz.core.sync.CommitId
import dev.jukz.core.sync.WorldSync
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.Transport
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * JGit-backed world snapshotting (spec §4.5). [commit] flushes the live save into a per-world git
 * repo; [pullLatest] fetches the host's offered snapshot (F4-A) over the live connection and resets
 * the working tree to it so a guest can take over hosting. The monotonic generation (read from the
 * sidecar / commit message) is the anti-stale guard. The [transport] dials the host's snapshot
 * endpoint — the same connection-server port the game uses, so the pull rides the one NAT traversal
 * that already works (no second port to forward).
 */
class JGitWorldSync(
    private val transport: Transport = DirectTcpTransport(),
) : WorldSync {

    override fun currentGeneration(saveDir: Path): Long =
        WorldIdSidecar.read(saveDir)?.generation ?: 0L

    override suspend fun commit(saveDir: Path, generation: Long): CommitId = withContext(Dispatchers.IO) {
        openOrInit(saveDir).use { git ->
            // Minecraft holds session.lock with an exclusive FileLock while the world is loaded, so a
            // plain `git add .` throws trying to read it. Exclude it (and untrack it if it ever slipped
            // in) so the snapshot can be built while the world is still open.
            ensureGitignore(saveDir)
            runCatching { git.rm().addFilepattern(IGNORED_LOCK).setCached(true).call() }
            git.add().addFilepattern(".").call()
            val rev = git.commit()
                .setMessage("$COMMIT_PREFIX$generation")
                .setAuthor("jukz", "noreply@jukz.dev")
                .setCommitter("jukz", "noreply@jukz.dev") // don't depend on a global git identity
                .setAllowEmpty(true)
                .call()
            CommitId(rev.name)
        }
    }

    /** A pack pulled to [packPath], ready to apply; [head] is the commit the working tree resets to. */
    data class Downloaded(val packPath: Path, val head: ObjectId)

    /**
     * Fetch the host's offered snapshot for [target] into [saveDir] before this node becomes host: this
     * is the convenience that downloads and applies in one shot (used where the host is still up at
     * takeover time). The handoff path splits it — [downloadSnapshot] then [applySnapshot] — so the
     * time-critical pull happens while the host is connected and the apply can come later. Non-fatal: a
     * missing offer, an unreachable port, or a bad download is logged and swallowed.
     */
    override suspend fun pullLatest(saveDir: Path, target: WorldRecord): Boolean = withContext(Dispatchers.IO) {
        val offer = target.snapshot
        if (offer == null) {
            JukzMod.logger.warn("jukz: no snapshot offer for {}; taking over with the local copy", target.worldId)
            return@withContext false
        }
        val downloaded = downloadSnapshot(offer) ?: return@withContext false
        try {
            applySnapshot(saveDir, downloaded, target.worldId, target.hostGeneration)
        } finally {
            Files.deleteIfExists(downloaded.packPath)
        }
    }

    /**
     * Pull the host's armed pack to a temp file over the SNAPSHOT channel and return it, or null on any
     * failure. This is the time-critical half of the handoff: it must run while the host is still
     * connected (its connection server is torn down shortly after it announces it is leaving), so it is
     * kicked off the moment the `HostLeaving` notice arrives — not deferred to the user's "take over"
     * click, which may come much later. The caller deletes [Downloaded.packPath] once applied.
     */
    suspend fun downloadSnapshot(offer: SnapshotOffer): Downloaded? = withContext(Dispatchers.IO) {
        runCatching {
            val pack = Files.createTempFile("jukz-snapshot", ".pack")
            try {
                Downloaded(pack, pull(offer, pack))
            } catch (e: Throwable) {
                Files.deleteIfExists(pack)
                throw e
            }
        }.getOrElse {
            JukzMod.logger.warn("jukz: snapshot download failed ({}); will take over with the local copy", it.message)
            null
        }
    }

    /**
     * Apply an already-[downloaded] pack into [saveDir]: index it into the local object store, hard-reset
     * the working tree to its head, and mirror the commit's generation into `jukz.dat` (falling back to
     * [fallbackGeneration]). Purely local — needs no network, so it works even after the host has gone.
     * Non-fatal: a failure leaves the existing local copy and returns false.
     */
    suspend fun applySnapshot(saveDir: Path, downloaded: Downloaded, worldId: WorldId, fallbackGeneration: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                applyPack(saveDir, downloaded.packPath, downloaded.head)
                mirrorGeneration(saveDir, worldId, fallbackGeneration)
                JukzMod.logger.info("jukz: applied snapshot {} for {}", downloaded.head.name, worldId)
                true
            }.getOrElse {
                JukzMod.logger.warn("jukz: snapshot apply failed ({}); taking over with the local copy", it.message)
                false
            }
        }

    /**
     * Pull the host's armed pack into [dest] over a [ConnectionType.SNAPSHOT] channel — the same
     * connection-server port the game already reaches, so it crosses NAT without a second forward.
     * Wire: write the gate token (UTF), read a status byte (1 = accepted), then the head commit id
     * (UTF), the pack length (long), and the pack bytes. Returns the head the guest resets to.
     */
    private suspend fun pull(offer: SnapshotOffer, dest: Path): ObjectId =
        transport.connect(Endpoint(offer.host, offer.port)).use { channel ->
            ConnectionType.SNAPSHOT.writeTo(channel)
            DataOutputStream(channel.outputStream()).apply { writeUTF(offer.token); flush() }
            val input = DataInputStream(channel.inputStream())
            require(input.readByte().toInt() == 1) { "snapshot rejected by host" }
            val head = input.readUTF()
            val size = input.readLong()
            Files.newOutputStream(dest).use { out -> copyExactly(input, out, size) }
            ObjectId.fromString(head)
        }

    /** Copy exactly [size] bytes from [input] to [out], failing if the stream ends early. */
    private fun copyExactly(input: DataInputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("snapshot truncated: $remaining of $size bytes missing")
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    /** Index the pack into the local repo and hard-reset the working tree to [head]. */
    private fun applyPack(saveDir: Path, packFile: Path, head: ObjectId) {
        openOrInit(saveDir).use { git ->
            Files.newInputStream(packFile).use { input ->
                git.repository.newObjectInserter().use { inserter ->
                    inserter.newPackParser(input).parse(NullProgressMonitor.INSTANCE)
                    inserter.flush()
                }
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(head.name).call()
        }
    }

    /** Read the generation back from the fetched commit message and write it into the sidecar. */
    private fun mirrorGeneration(saveDir: Path, worldId: WorldId, fallbackGeneration: Long) {
        openOrInit(saveDir).use { git ->
            val head = git.repository.resolve("HEAD") ?: return
            val message = RevWalk(git.repository).use { it.parseCommit(head).fullMessage }
            val generation = COMMIT_GENERATION.find(message)?.groupValues?.get(1)?.toLongOrNull()
                ?: fallbackGeneration
            WorldIdSidecar.write(saveDir, WorldIdSidecar.Info(worldId.uuid, generation))
        }
    }

    private fun openOrInit(saveDir: Path): Git =
        if (Files.exists(saveDir.resolve(".git"))) {
            Git.open(saveDir.toFile())
        } else {
            Git.init().setDirectory(saveDir.toFile()).call()
        }

    /** Ensure session.lock is git-ignored so a `git add` over a loaded world doesn't choke on the lock. */
    private fun ensureGitignore(saveDir: Path) {
        val gitignore = saveDir.resolve(".gitignore")
        if (!Files.exists(gitignore)) {
            runCatching { Files.writeString(gitignore, "/$IGNORED_LOCK\n") }
        }
    }

    private companion object {
        const val COMMIT_PREFIX = "jukz generation "
        const val IGNORED_LOCK = "session.lock"
        val COMMIT_GENERATION = Regex("""jukz generation (\d+)""")
    }
}
