package dev.jukz.world

import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.HolderLookup
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import java.util.UUID

/**
 * Persists the permanent world UUID and the monotonic host generation as a [SavedData]
 * attached to the overworld. Uses the Minecraft 1.21.1 SavedData shape (verified):
 * `Factory(Supplier, BiFunction<CompoundTag, HolderLookup.Provider, T>, DataFixTypes)` and
 * `computeIfAbsent(Factory, String id)` — this differs from 1.21.5+.
 */
class WorldIdState(
    var worldId: UUID,
    var generation: Long,
) : SavedData() {

    override fun save(nbt: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        nbt.putUUID(KEY_WORLD_ID, worldId)
        nbt.putLong(KEY_GENERATION, generation)
        return nbt
    }

    /** Bump the generation (called before announcing as host) and mark for save. */
    fun incrementGeneration(): Long {
        generation += 1
        setDirty()
        return generation
    }

    companion object {
        const val STATE_ID = "jukz_world_id"
        private const val KEY_WORLD_ID = "world_id"
        private const val KEY_GENERATION = "generation"

        val TYPE: SavedData.Factory<WorldIdState> = SavedData.Factory(
            { WorldIdState(UUID.randomUUID(), 0L) },
            { nbt, _ -> fromNbt(nbt) },
            DataFixTypes.LEVEL,
        )

        private fun fromNbt(nbt: CompoundTag): WorldIdState =
            WorldIdState(nbt.getUUID(KEY_WORLD_ID), nbt.getLong(KEY_GENERATION))

        /** Get or create the world's id state, ensuring a freshly-created id is persisted. */
        fun get(world: ServerLevel): WorldIdState {
            val state = world.dataStorage.computeIfAbsent(TYPE, STATE_ID)
            // A newly-created state holds a random UUID that must be flushed to disk.
            state.setDirty()
            return state
        }
    }
}
