package dev.jukz.world

import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.util.WorldSavePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * A plain-NBT sidecar (`<save>/jukz.dat`) holding the world UUID + generation. It exists so the
 * mod can read the world identity with plain file IO BEFORE the integrated server starts — needed
 * to query discovery and decide guest-vs-host without spinning up the world first.
 */
object WorldIdSidecar {
    private const val FILE = "jukz.dat"
    private const val KEY_WORLD_ID = "world_id"
    private const val KEY_GENERATION = "generation"

    data class Info(val worldId: UUID, val generation: Long)

    private fun fileIn(saveRoot: Path): Path = saveRoot.resolve(FILE)

    /** Read the sidecar directly from a save directory (pre-server-start path). */
    fun read(saveRoot: Path): Info? {
        val path = fileIn(saveRoot)
        if (!Files.exists(path)) return null
        val nbt = NbtIo.read(path) ?: return null
        return Info(nbt.getUuid(KEY_WORLD_ID), nbt.getLong(KEY_GENERATION))
    }

    /** Mirror the current id+generation into the sidecar (called while the world is loaded). */
    fun write(server: MinecraftServer, state: WorldIdState) {
        write(server.getSavePath(WorldSavePath.ROOT), Info(state.worldId, state.generation))
    }

    fun write(saveRoot: Path, info: Info) {
        val nbt = NbtCompound()
        nbt.putUuid(KEY_WORLD_ID, info.worldId)
        nbt.putLong(KEY_GENERATION, info.generation)
        Files.createDirectories(saveRoot)
        NbtIo.write(nbt, fileIn(saveRoot))
    }
}
