package dev.jukz.world

import net.minecraft.datafixer.DataFixTypes
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.PersistentState
import java.util.UUID

/**
 * Persists the permanent world UUID and the monotonic host generation as a [PersistentState]
 * attached to the overworld. Uses the Minecraft 1.21.1 PersistentState shape (verified):
 * `Type(Supplier, BiFunction<NbtCompound, WrapperLookup, T>, DataFixTypes)` and
 * `getOrCreate(Type, String id)` — this differs from 1.21.5+.
 */
class WorldIdState(
    var worldId: UUID,
    var generation: Long,
) : PersistentState() {

    override fun writeNbt(nbt: NbtCompound, registries: RegistryWrapper.WrapperLookup): NbtCompound {
        nbt.putUuid(KEY_WORLD_ID, worldId)
        nbt.putLong(KEY_GENERATION, generation)
        return nbt
    }

    /** Bump the generation (called before announcing as host) and mark for save. */
    fun incrementGeneration(): Long {
        generation += 1
        markDirty()
        return generation
    }

    companion object {
        const val STATE_ID = "jukz_world_id"
        private const val KEY_WORLD_ID = "world_id"
        private const val KEY_GENERATION = "generation"

        val TYPE: Type<WorldIdState> = Type(
            { WorldIdState(UUID.randomUUID(), 0L) },
            { nbt, _ -> fromNbt(nbt) },
            DataFixTypes.LEVEL,
        )

        private fun fromNbt(nbt: NbtCompound): WorldIdState =
            WorldIdState(nbt.getUuid(KEY_WORLD_ID), nbt.getLong(KEY_GENERATION))

        /** Get or create the world's id state, ensuring a freshly-created id is persisted. */
        fun get(world: ServerWorld): WorldIdState {
            val state = world.persistentStateManager.getOrCreate(TYPE, STATE_ID)
            // A newly-created state holds a random UUID that must be flushed to disk.
            state.markDirty()
            return state
        }
    }
}
