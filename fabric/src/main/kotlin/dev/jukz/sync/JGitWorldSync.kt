package dev.jukz.sync

import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.sync.CommitId
import dev.jukz.core.sync.WorldSync
import dev.jukz.world.WorldIdSidecar
import org.eclipse.jgit.api.Git
import java.nio.file.Files
import java.nio.file.Path

/**
 * JGit-backed world snapshotting (spec §4.5). [commit] and the repo init are real and run against
 * JGit; [pullLatest] is flagged because fetching a snapshot from the host needs the live P2P
 * transport. The monotonic generation (read from the sidecar) is the anti-stale guard.
 */
class JGitWorldSync : WorldSync {

    override fun currentGeneration(saveDir: Path): Long =
        WorldIdSidecar.read(saveDir)?.generation ?: 0L

    override suspend fun commit(saveDir: Path, generation: Long): CommitId {
        openOrInit(saveDir).use { git ->
            git.add().addFilepattern(".").call()
            val rev = git.commit()
                .setMessage("jukz generation $generation")
                .setAuthor("jukz", "noreply@jukz.dev")
                .setAllowEmpty(true)
                .call()
            return CommitId(rev.name)
        }
    }

    override suspend fun pullLatest(saveDir: Path, target: WorldRecord) {
        // TODO(live-network): fetch the worldId's pack from the host over the jukz transport and
        //  reset the working tree to the target generation BEFORE this node becomes host.
        throw NotImplementedError("world pull requires the live P2P transport")
    }

    private fun openOrInit(saveDir: Path): Git =
        if (Files.exists(saveDir.resolve(".git"))) {
            Git.open(saveDir.toFile())
        } else {
            Git.init().setDirectory(saveDir.toFile()).call()
        }
}
