package dev.jukz.sync

import dev.jukz.world.WorldIdSidecar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID

/**
 * [JGitWorldSync.headCommit] exposes the local world repo's current snapshot commit so the takeover
 * lineage guard ([dev.jukz.core.sync.SnapshotLineage]) can tell our applied snapshot from a divergent
 * cloud sibling at the same generation.
 */
class JGitWorldSyncHeadTest {

    @Test
    fun `headCommit returns null when there is no repo yet`() {
        val dir = Files.createTempDirectory("jukz-nohead")
        assertNull(JGitWorldSync().headCommit(dir))
    }

    @Test
    fun `headCommit returns the commit a freshly-built snapshot reset to`() {
        val dir = Files.createTempDirectory("jukz-head")
        Files.writeString(dir.resolve("level.dat"), "world")
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(UUID.randomUUID(), 3))
        val sync = JGitWorldSync()

        val pack = SnapshotPack.build(dir, sync) ?: error("snapshot pack should build")

        assertEquals(pack.head, JGitWorldSync().headCommit(dir))
    }
}
