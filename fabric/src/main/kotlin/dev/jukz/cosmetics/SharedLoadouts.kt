package dev.jukz.cosmetics

import dev.jukz.net.LoadoutPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Host side of the cosmetics of players without a Mojang account: same as [dev.jukz.skins.SharedSkins],
 * remembers what each player sent and passes it on to everyone else. Memory only, while the world is open.
 * Receivers check the items against their catalog (only free ones are worn).
 */
object SharedLoadouts {
    private val loadouts = ConcurrentHashMap<UUID, LoadoutPayload>()

    fun receive(server: MinecraftServer, player: ServerPlayer, payload: LoadoutPayload) {
        val loadout = LoadoutPayload(player.uuid, payload.loadout)
        loadouts[player.uuid] = loadout
        server.playerList.players.filter { it.uuid != player.uuid }.forEach { send(it, loadout) }
    }

    fun greet(player: ServerPlayer) {
        loadouts.values.filter { it.owner != player.uuid }.forEach { send(player, it) }
    }

    fun forget(player: UUID) {
        loadouts.remove(player)
    }

    fun clear() = loadouts.clear()

    private fun send(player: ServerPlayer, loadout: LoadoutPayload) {
        if (ServerPlayNetworking.canSend(player, LoadoutPayload.ID)) ServerPlayNetworking.send(player, loadout)
    }
}
