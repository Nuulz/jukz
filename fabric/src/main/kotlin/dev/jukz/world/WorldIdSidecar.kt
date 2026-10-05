package dev.jukz.world

import dev.jukz.compat.putUuid
import dev.jukz.compat.compound
import dev.jukz.compat.long
import dev.jukz.compat.uuid
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
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
        return Info(nbt.uuid(KEY_WORLD_ID) ?: return null, nbt.long(KEY_GENERATION) ?: 0L)
    }

    /** Mirror the current id+generation into the sidecar (called while the world is loaded). */
    fun write(server: MinecraftServer, state: WorldIdState) {
        write(server.getWorldPath(LevelResource.ROOT), Info(state.worldId, state.generation))
    }

    /**
     * The world's real generation while it is closed: the larger of jukz.dat and the world's own
     * `data/jukz_world_id.dat`. Saves written before jukz.dat followed every bump lag one behind.
     */
    fun generation(saveRoot: Path): Long? {
        val sidecar = runCatching { read(saveRoot)?.generation }.getOrNull()
        val inWorld = WorldIdState.FILES.firstNotNullOfOrNull { file ->
            runCatching {
                NbtIo.readCompressed(saveRoot.resolve(file), net.minecraft.nbt.NbtAccounter.unlimitedHeap())
                    .compound("data").long("generation")
            }.getOrNull()
        }
        return listOfNotNull(sidecar, inWorld).maxOrNull()
    }

    fun write(saveRoot: Path, info: Info) {
        val nbt = CompoundTag()
        nbt.putUuid(KEY_WORLD_ID, info.worldId)
        nbt.putLong(KEY_GENERATION, info.generation)
        Files.createDirectories(saveRoot)
        NbtIo.write(nbt, fileIn(saveRoot))
    }
}
