package dev.jukz.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class WorldSaveLocatorTest {

    @Test
    fun `finds the save folder carrying the world id`() {
        val saves = Files.createTempDirectory("jukz-saves")
        val target = UUID.randomUUID()
        Files.createDirectory(saves.resolve("WorldA")).also { WorldIdSidecar.write(it, WorldIdSidecar.Info(UUID.randomUUID(), 1)) }
        Files.createDirectory(saves.resolve("WorldB")).also { WorldIdSidecar.write(it, WorldIdSidecar.Info(target, 5)) }
        Files.createDirectory(saves.resolve("Vanilla")) // no jukz.dat -> ignored

        assertEquals("WorldB", WorldSaveLocator.findLevelName(saves, target))
    }

    @Test
    fun `returns null when no save carries the world id`() {
        val saves = Files.createTempDirectory("jukz-saves2")
        Files.createDirectory(saves.resolve("WorldA")).also { WorldIdSidecar.write(it, WorldIdSidecar.Info(UUID.randomUUID(), 1)) }

        assertNull(WorldSaveLocator.findLevelName(saves, UUID.randomUUID()))
    }

    @Test
    fun `returns null for a missing saves directory`() {
        assertNull(WorldSaveLocator.findLevelName(Path.of("does-not-exist-jukz-saves"), UUID.randomUUID()))
    }
}
