package dev.jukz.sync

import dev.jukz.JukzMod
import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import dev.jukz.core.sync.CommitId
import dev.jukz.core.sync.WorldSync
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectChannelDialer
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
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
 * sidecar / commit message) is the anti-stale guard. The [dialer] opens the SNAPSHOT channel the
 * same way play connects — a [DialTarget.Direct] for a directly-reachable host, or a
 * [DialTarget.ViaRelay] when the guest reached the host through the relay — so the pull rides the
 * exact NAT traversal that already carries the game (no second port, and it works over the relay).
 */
class JGitWorldSync(
    private val dialer: ChannelDialer = DirectChannelDialer(),
) : WorldSync {

    override fun currentGeneration(saveDir: Path): Long =
        WorldIdSidecar.read(saveDir)?.generation ?: 0L

    /**
     * The current snapshot commit id of the local world repo in [saveDir], or null when there is no
     * repo (no snapshot has ever been built). Used with the generation to identify our local snapshot's
     * lineage so a takeover never replaces it with a same-generation but divergent cloud sibling.
     */
    fun headCommit(saveDir: Path): String? = runCatching {
        if (!Files.exists(saveDir.resolve(".git"))) return null
        Git.open(saveDir.toFile()).use { it.repository.resolve("HEAD")?.name }
    }.getOrNull()

    override suspend fun commit(saveDir: Path, generation: Long): CommitId = withContext(Dispatchers.IO) {
        openOrInit(saveDir).use { git ->
            // Minecraft holds session.lock with an exclusive FileLock while the world is loaded, so a
            // plain `git add .` throws trying to read it. Exclude it (and untrack it if it ever slipped
            // in) so the snapshot can be built while the world is still open.
            ensureGitignore(saveDir)
            stageWorkingTree(git) // like `git add -A`, with region files stored open (RegionCodec)
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
    suspend fun downloadSnapshot(offer: SnapshotOffer): Downloaded? =
        downloadSnapshot(DialTarget.Direct(Endpoint(offer.host, offer.port)), offer.token)

    /**
     * Pull the host's armed pack over the connection identified by [target] — the SAME path play
     * connected over, so a guest that reached the host through the relay ([DialTarget.ViaRelay]) pulls
     * the snapshot through that relay session too (the host serves SNAPSHOT on the one listener the
     * relay already bridges). [token] gates the download. Null on any failure (non-fatal).
     */
    suspend fun downloadSnapshot(target: DialTarget, token: String): Downloaded? = withContext(Dispatchers.IO) {
        runCatching {
            val pack = Files.createTempFile("jukz-snapshot", ".pack")
            try {
                Downloaded(pack, pull(target, token, pack))
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
                stripInheritedHostPlayer(saveDir)
                mirrorGeneration(saveDir, worldId, fallbackGeneration)
                JukzMod.logger.info("jukz: applied snapshot {} for {}", downloaded.head.name, worldId)
                true
            }.getOrElse {
                JukzMod.logger.warn("jukz: snapshot apply failed ({}); taking over with the local copy", it.message)
                false
            }
        }

    /**
     * Read one file of a downloaded snapshot without applying it anywhere: the pack is indexed into a
     * throwaway in-memory repo and [path] is looked up in the head commit's tree. Used to pick the
     * world key out of a handoff the guest declined, so the backup it then uploads can be signed.
     */
    fun readFileAtHead(downloaded: Downloaded, path: String): ByteArray? = runCatching {
        val repo = InMemoryRepository(DfsRepositoryDescription("jukz-snapshot"))
        repo.newObjectInserter().use { inserter ->
            SnapshotCodec.open(downloaded.packPath).use { input ->
                inserter.newPackParser(input).parse(NullProgressMonitor.INSTANCE)
            }
            inserter.flush()
        }
        RevWalk(repo).use { walk ->
            val tree = walk.parseCommit(downloaded.head).tree
            TreeWalk.forPath(repo, path, tree)?.use { repo.open(it.getObjectId(0)).bytes }
        }
    }.getOrNull()

    /**
     * Bound the host's local `.git` on disk: once it grows past [thresholdBytes], run `git gc` to
     * repack the reachable current world and prune the now-unreachable history. The snapshot build
     * re-roots HEAD at a parentless commit, so every prior commit/region-version is unreachable and
     * collectable. [graceMillis] keeps very recent objects, so a gc racing a concurrent commit/apply
     * can never prune that operation's fresh objects. Best-effort and meant for a background thread:
     * a failure (e.g. a lock race) is logged and skipped, to be retried on a later, quieter close.
     */
    fun compactIfNeeded(
        saveDir: Path,
        thresholdBytes: Long = GC_THRESHOLD_BYTES,
        graceMillis: Long = GC_GRACE_MS,
    ) {
        if (runCatching { gitDirSize(saveDir) }.getOrDefault(0L) < thresholdBytes) return
        runCatching {
            // Repos created before the reflog was disabled still have entries pinning old commits;
            // drop them (jukz never reads the reflog) so gc can actually reclaim the re-rooted history.
            deleteRecursively(saveDir.resolve(".git").resolve("logs"))
            Git.open(saveDir.toFile()).use { git ->
                git.gc().setExpire(java.util.Date(System.currentTimeMillis() - graceMillis)).call()
            }
            JukzMod.logger.info("jukz: compacted local world repo")
        }.onFailure { JukzMod.logger.info("jukz: world repo compaction skipped ({})", it.message) }
    }

    /** Recursively delete [path] (deepest entries first); best-effort, used to drop the reflog. */
    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted { a, b -> b.compareTo(a) }.forEach { p -> runCatching { Files.delete(p) } }
        }
    }

    /** Total bytes under `<saveDir>/.git`, or 0 when there is no repo yet. */
    private fun gitDirSize(saveDir: Path): Long {
        val gitDir = saveDir.resolve(".git")
        if (!Files.exists(gitDir)) return 0L
        return Files.walk(gitDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }

    /**
     * Pull the host's armed pack into [dest] over a [ConnectionType.SNAPSHOT] channel — the same
     * connection-server port the game already reaches, so it crosses NAT without a second forward.
     * Wire: write the gate token (UTF), read a status byte (1 = accepted), then the head commit id
     * (UTF), the pack length (long), and the pack bytes. Returns the head the guest resets to.
     */
    private suspend fun pull(target: DialTarget, token: String, dest: Path): ObjectId =
        dialer.dial(target).use { channel ->
            ConnectionType.SNAPSHOT.writeTo(channel)
            DataOutputStream(channel.outputStream()).apply { writeUTF(token); flush() }
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
            SnapshotCodec.open(packFile).use { input ->
                git.repository.newObjectInserter().use { inserter ->
                    inserter.newPackParser(input).parse(NullProgressMonitor.INSTANCE)
                    inserter.flush()
                }
            }
            checkout(git, saveDir, head)
        }
    }

    /**
     * Stage every non-ignored file of the working tree (new, changed and deleted ones), like
     * `git add -A`, except that region files are stored open ([RegionCodec.open]) so the snapshot
     * compresses as a whole.
     */
    private fun stageWorkingTree(git: Git) {
        val repo = git.repository
        val dirCache = repo.lockDirCache()
        try {
            val builder = dirCache.builder()
            repo.newObjectInserter().use { inserter ->
                TreeWalk(repo).use { walk ->
                    walk.addTree(FileTreeIterator(repo))
                    walk.isRecursive = false
                    while (walk.next()) {
                        val file = walk.getTree(0, FileTreeIterator::class.java)
                        if (file.isEntryIgnored) continue
                        if (walk.isSubtree) {
                            walk.enterSubtree()
                            continue
                        }
                        val path = walk.pathString
                        val mode = file.entryFileMode
                        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                        val bytes = runCatching { Files.readAllBytes(repo.workTree.toPath().resolve(path)) }.getOrNull() ?: continue
                        val stored = if (RegionCodec.isRegionPath(path)) RegionCodec.open(bytes) ?: bytes else bytes
                        builder.add(DirCacheEntry(path).apply {
                            fileMode = mode
                            setObjectId(inserter.insert(Constants.OBJ_BLOB, stored))
                            setLength(stored.size)
                        })
                    }
                }
                inserter.flush()
            }
            builder.commit()
        } finally {
            dirCache.unlock()
        }
    }

    /**
     * Point HEAD at [head] and make the working tree match it, like `git reset --hard`: files tracked
     * before but absent from [head] are deleted, region files are written back in Minecraft's own format
     * ([RegionCodec.close]); untracked files are left alone.
     */
    private fun checkout(git: Git, saveDir: Path, head: ObjectId) {
        val repo = git.repository
        val tree = RevWalk(repo).use { it.parseCommit(head).tree }
        val before = runCatching { repo.readDirCache().let { dc -> (0 until dc.entryCount).map { dc.getEntry(it).pathString }.toSet() } }
            .getOrDefault(emptySet())
        val after = mutableSetOf<String>()
        repo.newObjectReader().use { reader ->
            TreeWalk(repo, reader).use { walk ->
                walk.addTree(tree)
                walk.isRecursive = true
                while (walk.next()) {
                    val path = walk.pathString
                    after += path
                    val bytes = reader.open(walk.getObjectId(0)).bytes
                    val target = saveDir.resolve(path)
                    Files.createDirectories(target.parent)
                    Files.write(target, if (RegionCodec.isRegionPath(path)) RegionCodec.close(bytes) else bytes)
                }
            }
            (before - after).forEach { runCatching { Files.deleteIfExists(saveDir.resolve(it)) } }
            val dirCache = repo.lockDirCache()
            try {
                dirCache.builder().apply {
                    addTree(ByteArray(0), 0, reader, tree)
                    commit()
                }
            } finally {
                dirCache.unlock()
            }
        }
        repo.updateRef(Constants.HEAD).apply {
            setNewObjectId(head)
            setForceUpdate(true)
            update()
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

    /**
     * Drop the singleplayer-owner player data baked into the snapshot's `level.dat` (`Data.Player`).
     * Minecraft loads that compound for whoever opens the world as host (`PlayerManager.loadPlayerData`
     * reads `SaveProperties.getPlayerData()` for `isHost` players, only falling back to
     * `playerdata/<uuid>.dat` when it is null). Without this, the player taking over would inherit the
     * PREVIOUS host's position, inventory, health and XP. Removing it makes `getPlayerData()` null, so
     * every player loads their OWN `playerdata/<uuid>.dat` (which the server saves for all players,
     * host included, and the pack carries) — returning players resume where they were, brand-new ones
     * spawn at the world spawn. Best-effort: a failure just leaves the inherited data.
     */
    private fun stripInheritedHostPlayer(saveDir: Path) {
        val levelDat = saveDir.resolve("level.dat")
        if (!Files.exists(levelDat)) return
        runCatching {
            val root = NbtIo.readCompressed(levelDat, NbtSizeTracker.ofUnlimitedBytes())
            val data = root.getCompound("Data")
            if (data.contains("Player")) {
                data.remove("Player")
                NbtIo.writeCompressed(root, levelDat)
                JukzMod.logger.info("jukz: cleared inherited host player from snapshot level.dat")
            }
        }.onFailure { JukzMod.logger.warn("jukz: could not strip inherited host player ({})", it.message) }
    }

    private fun openOrInit(saveDir: Path): Git {
        val git = if (Files.exists(saveDir.resolve(".git"))) {
            Git.open(saveDir.toFile())
        } else {
            Git.init().setDirectory(saveDir.toFile()).call()
        }
        // jukz uses git purely as snapshot transport — no branches, no history browsing, no reflog.
        // With the reflog on, every ref update (including the snapshot re-root) pins the old commit
        // for ~90 days, so compactIfNeeded could never reclaim a re-rooted history. Turn it off so an
        // unreferenced commit is immediately collectable.
        runCatching {
            val cfg = git.repository.config
            if (cfg.getString("core", null, "logAllRefUpdates") != "false") {
                cfg.setBoolean("core", null, "logAllRefUpdates", false)
                cfg.save()
            }
        }
        return git
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

        /** Only compact once the local `.git` grows past this (the reachable world is far smaller). */
        const val GC_THRESHOLD_BYTES = 64L * 1024 * 1024 // 64 MiB: open regions pile up fast, a gc packs them back to ~the world size
        /** Never prune objects newer than this — protects a concurrent commit/apply from the gc. */
        const val GC_GRACE_MS = 10L * 60 * 1000 // 10 minutes
    }
}
