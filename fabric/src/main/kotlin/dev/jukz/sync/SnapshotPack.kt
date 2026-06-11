package dev.jukz.sync

import dev.jukz.JukzMod
import dev.jukz.core.sync.WorldSync
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.pack.PackWriter
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * Builds the one-shot JGit pack a leaving host serves for a take-over (F4 handoff): the save is
 * committed, then every object reachable from HEAD is packed into a single byte array. The pack is
 * handed to [dev.jukz.core.host.HostController.offerSnapshot], which serves it over the live
 * connection-server port — so this class never touches the network or Minecraft, and is unit-tested
 * against temp repos via [SnapshotHandoffTest].
 */
object SnapshotPack {

    /** A packed save plus the head commit id the guest resets its working tree to. */
    data class Pack(val bytes: ByteArray, val head: String)

    /**
     * Commit the live save at its current generation, then pack everything reachable from HEAD.
     * Returns null when there is nothing to serve (commit failed, or the repo has no commit), so the
     * caller can just withdraw without offering a handoff.
     */
    fun build(saveDir: Path, sync: WorldSync): Pack? =
        runCatching {
            val generation = sync.currentGeneration(saveDir)
            runBlocking { sync.commit(saveDir, generation) }
            Git.open(saveDir.toFile()).use { git ->
                val head = git.repository.resolve("HEAD") ?: return null
                val out = ByteArrayOutputStream()
                PackWriter(git.repository).use { pw ->
                    pw.preparePack(NullProgressMonitor.INSTANCE, setOf(head), emptySet<ObjectId>())
                    pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, out)
                }
                Pack(out.toByteArray(), head.name)
            }
        }.getOrElse {
            JukzMod.logger.warn("jukz: snapshot pack build failed ({} / cause: {}); no handoff offer", it.message, it.cause?.message)
            null
        }
}
