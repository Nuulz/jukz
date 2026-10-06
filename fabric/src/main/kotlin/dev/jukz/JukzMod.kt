package dev.jukz

import dev.jukz.compat.isOwner
import dev.jukz.config.JukzState
import dev.jukz.core.model.WorldId
import dev.jukz.net.WorldAccessPayload
import dev.jukz.net.SkinPayload
import dev.jukz.skins.SharedSkins
import dev.jukz.runtime.HostSession
import dev.jukz.world.WorldIdSidecar
import dev.jukz.world.WorldIdState
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
//? if >=26.2 {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents
*///?} else {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents
//?}
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

/**
 * Common entrypoint. Wires the integrated-server lifecycle (which fires for singleplayer too):
 *  - ServerWorldEvents.LOAD (overworld) -> ensure the world UUID + generation exist and mirror them
 *    to the pre-start sidecar.
 *  - SERVER_STOPPING -> withdraw whatever the player shared (covers exit-to-menu AND quit, unlike
 *    CLIENT_STOPPING which would leak a stale host record).
 *
 * Sharing is explicit (the client "Play together" button drives `ShareCoordinator`), so there is no
 * host-on-start hook here — a singleplayer world stays private until the player opens it.
 */
object JukzMod : ModInitializer {
    const val MOD_ID = "Joining Every Known Zone (JUKZ)"
    val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        JukzState.captureStartup() // before anything writes jukz's config files
        //? if >=26.2 {
        /*PayloadTypeRegistry.clientboundPlay().register(WorldAccessPayload.ID, WorldAccessPayload.CODEC)
        *///?} else {
        PayloadTypeRegistry.playS2C().register(WorldAccessPayload.ID, WorldAccessPayload.CODEC)
        //?}
        // Player-chosen skins travel both ways over the game connection (see SharedSkins).
        //? if >=26.2 {
        /*PayloadTypeRegistry.clientboundPlay().register(SkinPayload.ID, SkinPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SkinPayload.ID, SkinPayload.CODEC)
        *///?} else {
        PayloadTypeRegistry.playS2C().register(SkinPayload.ID, SkinPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SkinPayload.ID, SkinPayload.CODEC)
        //?}
        ServerPlayNetworking.registerGlobalReceiver(SkinPayload.ID) { payload, context ->
            val player = context.player()
            player.level().server?.let { SharedSkins.receive(it, player, payload) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> SharedSkins.forget(handler.player.uuid) }

        // A player the server let in gets the world key + handoff gate, over the game connection.
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            if (!server.isOwner(handler.player.gameProfile)) sendWorldAccess(handler.player)
            SharedSkins.greet(handler.player)
        }

        ServerLifecycleEvents.SERVER_STARTING.register { _ ->
            HostSession.onServerStarting()
        }

        //? if >=26.2 {
        /*ServerLevelEvents.LOAD.register { server, world ->
        *///?} else {
        ServerWorldEvents.LOAD.register { server, world ->
        //?}
            if (world.dimension() == Level.OVERWORLD) {
                val state = WorldIdState.get(world)
                WorldIdSidecar.write(server, state)
                logger.info("jukz world {} (generation {})", state.worldId, state.generation)
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            SharedSkins.clear()
            HostSession.markServerStopped()
            // Hand the save dir to the session so it can hand off to any connected guest (over the live
            // control channel) before withdrawing. Whether a guest is connected is read from the
            // connection server, not the player list (which is already being torn down here).
            val saveDir = runCatching { server.getWorldPath(LevelResource.ROOT) }.getOrNull()
            // The world's id + generation come from the live WorldIdState — authoritative even when
            // auto-hosting never finished announcing, so a quick open->close still backs the world up.
            val state = runCatching { WorldIdState.get(server.overworld()) }.getOrNull()
            // Arm the cloud backup whenever the world is eligible (rendezvous + access open), with or
            // without guests: a guest-less close uploads directly, and a close mid-handoff falls back to
            // the upload when no guest takes over. Set explicitly each close so a prior world's decision
            // never leaks forward.
            dev.jukz.runtime.GhostUpload.setArmed(
                saveDir != null && dev.jukz.client.HostCoordinator.shouldUploadGhost(saveDir),
            )
            HostSession.onServerStopping(
                saveDir,
                state?.let { WorldId.of(it.worldId) },
                state?.generation ?: 0L,
            ) { runCatching { server.saveEverything(true, true, true) } }
        }

        logger.info("jukz initialized")
    }

    /** Hand [player] the hosted world's key and handoff gate, if hosting and their client has jukz. */
    fun sendWorldAccess(player: ServerPlayer) {
        val payload = HostSession.accessPayload() ?: return
        if (ServerPlayNetworking.canSend(player, WorldAccessPayload.ID)) ServerPlayNetworking.send(player, payload)
    }

    /** Re-send to everyone but the host (a re-announce starts a session with a new gate). */
    fun broadcastWorldAccess(server: MinecraftServer) {
        server.playerList.players.filterNot { server.isOwner(it.gameProfile) }.forEach(::sendWorldAccess)
    }
}
