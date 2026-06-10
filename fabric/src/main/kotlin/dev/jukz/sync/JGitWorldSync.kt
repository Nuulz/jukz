package dev.jukz.sync

import dev.jukz.JukzMod
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.sync.CommitId
import dev.jukz.core.sync.WorldSync
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * JGit-backed world snapshotting (spec §4.5). [commit] flushes the live save into a per-world git
 * repo; [pullLatest] fetches the host's offered snapshot (F4-A) and resets the working tree to it so
 * a guest can take over hosting. The monotonic generation (read from the sidecar / commit message) is
 * the anti-stale guard.
 */
class JGitWorldSync : WorldSync {

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

    /**
     * Fetch the host's offered snapshot for [target] into [saveDir] before this node becomes host:
     * stream the pack to a temp file, index it into the local object store, reset the working tree to
     * the fetched commit, and mirror the commit's generation into `jukz.dat`. Every failure mode —
     * no snapshot offer in the record (the host crashed or its lease expired), an unreachable port, a
     * bad download — is logged and swallowed, so the handoff is never blocked: the node keeps whatever
     * local state it already has.
     */
    override suspend fun pullLatest(saveDir: Path, target: WorldRecord): Boolean = withContext(Dispatchers.IO) {
        val offer = target.snapshot
        if (offer == null) {
            JukzMod.logger.warn("jukz: no snapshot offer for {}; taking over with the local copy", target.worldId)
            return@withContext false
        }
        runCatching {
            val pack = Files.createTempFile("jukz-snapshot", ".pack")
            try {
                val head = download(offer.url(), pack)
                applyPack(saveDir, pack, head)
                mirrorGeneration(saveDir, target)
                JukzMod.logger.info("jukz: pulled snapshot {} for {}", head.name, target.worldId)
            } finally {
                Files.deleteIfExists(pack)
            }
            true
        }.getOrElse {
            JukzMod.logger.warn("jukz: snapshot pull failed ({}); taking over with the local copy", it.message)
            false
        }
    }

    /** GET the pack to [dest], returning the HEAD commit id the host advertised in its header. */
    private fun download(url: String, dest: Path): ObjectId {
        val client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(dest))
        require(response.statusCode() == 200) { "snapshot HTTP ${response.statusCode()}" }
        val headHex = response.headers().firstValue(SnapshotProtocol.HEADER_HEAD)
            .orElseThrow { IllegalStateException("snapshot response missing ${SnapshotProtocol.HEADER_HEAD}") }
        return ObjectId.fromString(headHex)
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
    private fun mirrorGeneration(saveDir: Path, target: WorldRecord) {
        openOrInit(saveDir).use { git ->
            val head = git.repository.resolve("HEAD") ?: return
            val message = RevWalk(git.repository).use { it.parseCommit(head).fullMessage }
            val generation = COMMIT_GENERATION.find(message)?.groupValues?.get(1)?.toLongOrNull()
                ?: target.hostGeneration
            WorldIdSidecar.write(saveDir, WorldIdSidecar.Info(target.worldId.uuid, generation))
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
