package dev.jukz.skins

import dev.jukz.net.SkinPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Host side of player-chosen skins: remembers the skin each player in the world sent and passes it on
 * to everyone else, and hands a newcomer the skins of those already there. Lives only in memory, for
 * as long as the world is open; nothing is stored or uploaded.
 */
object SharedSkins {
    private val skins = ConcurrentHashMap<UUID, SkinPayload>()

    /** [player] sent their skin: keep it (as theirs, whatever owner they claimed) and pass it on. */
    fun receive(server: MinecraftServer, player: ServerPlayer, payload: SkinPayload) {
        if (!SkinPayload.isSkinPng(payload.png)) return
        val skin = SkinPayload(player.uuid, payload.slim, payload.png)
        skins[player.uuid] = skin
        server.playerList.players.filter { it.uuid != player.uuid }.forEach { send(it, skin) }
    }

    /** A player joined: give them every skin already shared in this world. */
    fun greet(player: ServerPlayer) {
        skins.values.filter { it.owner != player.uuid }.forEach { send(player, it) }
    }

    fun forget(player: UUID) {
        skins.remove(player)
    }

    fun clear() = skins.clear()

    private fun send(player: ServerPlayer, skin: SkinPayload) {
        if (ServerPlayNetworking.canSend(player, SkinPayload.ID)) ServerPlayNetworking.send(player, skin)
    }
}
