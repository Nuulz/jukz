package dev.jukz.world

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class WorldIdSidecarGenerationTest {
    private fun worldData(dir: Path, generation: Long) {
        Files.createDirectories(dir.resolve("data"))
        val root = CompoundTag().apply { put("data", CompoundTag().apply { putUUID("world_id", UUID.randomUUID()); putLong("generation", generation) }) }
        NbtIo.writeCompressed(root, dir.resolve("data").resolve("jukz_world_id.dat"))
    }

    @Test
    fun `a jukz dat left one behind reads the world's own generation`(@TempDir dir: Path) {
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(UUID.randomUUID(), 1))
        worldData(dir, 2)
        assertEquals(2L, WorldIdSidecar.generation(dir))
    }

    @Test
    fun `either one alone is enough, and neither means no generation`(@TempDir dir: Path) {
        assertNull(WorldIdSidecar.generation(dir))
        WorldIdSidecar.write(dir, WorldIdSidecar.Info(UUID.randomUUID(), 5))
        assertEquals(5L, WorldIdSidecar.generation(dir))
        worldData(dir, 3)
        assertEquals(5L, WorldIdSidecar.generation(dir), "the larger wins")
    }
}
