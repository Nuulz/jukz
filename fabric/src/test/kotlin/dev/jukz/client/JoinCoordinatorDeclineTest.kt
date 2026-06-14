package dev.jukz.client

import dev.jukz.core.model.WorldId
import dev.jukz.runtime.GhostUpload
import dev.jukz.sync.JGitWorldSync
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Declining a live handoff must not lose the world. The leaving host counts the guest's eager prefetch
 * as "downloaded by a guest" and withdraws WITHOUT a cloud backup, so the prefetched pack the guest is
 * holding is the only copy of the latest state. On decline it must be armed for the R2 upload — carrying
 * the leaving host's generation so the cloud copy fences correctly — not dropped (the 2026-06-14 loss).
 */
class JoinCoordinatorDeclineTest {

    @Test
    fun `declining a handoff arms the prefetched snapshot for the cloud upload`() {
        val worldId = WorldId.random()
        val head = ObjectId.fromString("0123456789012345678901234567890123456789")
        val packBytes = byteArrayOf(1, 2, 3, 4, 5)
        val packPath = Files.createTempFile("jukz-decline", ".pack")
        Files.write(packPath, packBytes)
        val downloaded = JGitWorldSync.Downloaded(packPath, head)
        GhostUpload.clear()
        try {
            val armed = JoinCoordinator.armDeclinedSnapshotForUpload(worldId, generation = 51L, downloaded)

            assertTrue(armed, "a declined handoff with a prefetched pack must arm an upload")
            val pending = GhostUpload.pending()
            assertNotNull(pending, "a declined handoff must arm the snapshot for the cloud, not drop it")
            assertEquals(worldId, pending!!.worldId)
            assertEquals(51L, pending.generation) // the leaving host's generation, for correct fencing
            assertEquals(head.name, pending.head)
            assertArrayEquals(packBytes, pending.pack)
        } finally {
            GhostUpload.clear()
            Files.deleteIfExists(packPath)
        }
    }
}
