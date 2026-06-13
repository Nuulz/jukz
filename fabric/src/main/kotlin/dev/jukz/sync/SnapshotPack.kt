package dev.jukz.sync

import dev.jukz.JukzMod
import dev.jukz.core.sync.WorldSync
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.pack.PackWriter
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevWalk
import java.io.ByteArrayOutputStream
import java.nio.file.Path

/**
 * Builds the one-shot JGit pack a leaving host serves for a take-over (F4 handoff): the save is
 * committed, then the CURRENT tree is packed (re-rooted as a parentless commit) into a single byte
 * array. The pack is handed to [dev.jukz.core.host.HostController.offerSnapshot], which serves it over
 * the live connection-server port — so this class never touches the network or Minecraft, and is
 * unit-tested against temp repos via [SnapshotHandoffTest].
 */
object SnapshotPack {

    /** A packed save plus the head commit id the guest resets its working tree to. */
    data class Pack(val bytes: ByteArray, val head: String)

    /**
     * Commit the live save at its current generation, then pack the current world state.
     * Returns null when there is nothing to serve (commit failed, or the repo has no commit), so the
     * caller can just withdraw without offering a handoff.
     */
    fun build(saveDir: Path, sync: WorldSync): Pack? =
        runCatching {
            val generation = sync.currentGeneration(saveDir)
            runBlocking { sync.commit(saveDir, generation) }
            Git.open(saveDir.toFile()).use { git ->
                val repo = git.repository
                val head = repo.resolve("HEAD") ?: return null
                // Pack a parentless (orphan) commit of the CURRENT tree, NOT the whole reachable
                // history. A normal commit chains to its parents, so packing everything reachable from
                // HEAD carried every snapshot ever committed — and Minecraft rewrites many region files
                // on each save, so the pack grew without bound (even a one-block edit ballooned it).
                // Re-rooting at the current tree keeps the pack ~the world size, and the guest's
                // hard-reset to this commit truncates the history on their side too.
                val (treeId, message) = RevWalk(repo).use { rw ->
                    val commit = rw.parseCommit(head)
                    commit.tree.id to commit.fullMessage // fullMessage preserves "jukz generation N"
                }
                val ident = PersonIdent("jukz", "noreply@jukz.dev")
                val rootCommit = CommitBuilder().apply {
                    setTreeId(treeId)
                    author = ident
                    committer = ident
                    setMessage(message)
                }
                val rootId = repo.newObjectInserter().use { inserter ->
                    val id = inserter.insert(rootCommit)
                    inserter.flush()
                    id
                }
                val out = ByteArrayOutputStream()
                PackWriter(repo).use { pw ->
                    pw.preparePack(NullProgressMonitor.INSTANCE, setOf(rootId), emptySet<ObjectId>())
                    pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, out)
                }
                Pack(out.toByteArray(), rootId.name)
            }
        }.getOrElse {
            JukzMod.logger.warn("jukz: snapshot pack build failed ({} / cause: {}); no handoff offer", it.message, it.cause?.message)
            null
        }
}
