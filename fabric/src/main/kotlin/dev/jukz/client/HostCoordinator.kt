package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.SupersededScreen
import dev.jukz.config.PersistentNodeId
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.ForwardingEndpointResolver
import dev.jukz.core.host.HostConnectionServer
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostResult
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.SystemClock
import dev.jukz.discovery.Discovery
import dev.jukz.runtime.HostSession
import dev.jukz.transport.LocalEndpointResolver
import dev.jukz.transport.UpnpPortForwarder
import dev.jukz.world.WorldAccessFlag
import dev.jukz.world.WorldIdState
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.MessageScreen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.server.integrated.IntegratedServer
import net.minecraft.text.Text
import net.minecraft.util.WorldSavePath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Auto-hosts the local world so others can join. Every jukz world is permanently shareable: whenever
 * it is opened and nobody else is already hosting it (the world-open interceptor would have joined
 * that host instead), the integrated server is opened to the network and the world is announced under
 * a fresh fencing generation. This is what keeps a live, canonical copy reachable while the owner is
 * playing — the core reason jukz exists. It is silent: it runs in the background and surfaces through
 * [dev.jukz.client.gui.HostInfoScreen], never a screen of its own.
 */
object HostCoordinator {

    private val starting = AtomicBoolean(false)

    /** Open + announce the running integrated world, unless already hosting or mid-start. Idempotent. */
    fun autoHost(server: IntegratedServer) {
        if (HostSession.isHosting) return
        if (accessDisabled(server)) {
            JukzMod.logger.info("jukz: access is closed for this world; not announcing")
            return
        }
        if (!starting.compareAndSet(false, true)) return
        Thread {
            try {
                report(
                    try {
                        runHost(server)
                    } catch (e: Throwable) {
                        HostResult.Failed(e.message ?: e.toString())
                    },
                )
            } finally {
                starting.set(false)
            }
        }.apply {
            isDaemon = true
            name = "jukz-host"
        }.start()
    }

    /**
     * Close access to this world (F4-D): write the per-world `jukz.access=disabled` flag, withdraw the
     * discovery record, and kick every connected guest. The flag is written synchronously so the UI
     * reads the new state back immediately; the withdraw + kick run off the render thread. The local
     * host player is left in the world — closing access takes the world private, it does not end it.
     */
    fun disableAccess(server: IntegratedServer) {
        WorldAccessFlag.disable(server.getSavePath(WorldSavePath.ROOT))
        Thread {
            HostSession.onServerStopping() // withdraw from discovery (no snapshot — the world stays open locally)
            val message = Text.literal("The host has closed access to this world.")
            server.execute {
                val kicked = server.playerManager.playerList.toList()
                    .filterNot { server.isHost(it.gameProfile) }
                kicked.forEach { it.networkHandler.disconnect(message) }
                JukzMod.logger.info("jukz: access closed; {} guest(s) disconnected", kicked.size)
            }
        }.apply { isDaemon = true; name = "jukz-access-close" }.start()
    }

    /** Re-open access to this world (F4-D): drop the flag and run the normal announce flow again. */
    fun enableAccess(server: IntegratedServer) {
        WorldAccessFlag.enable(server.getSavePath(WorldSavePath.ROOT))
        autoHost(server)
    }

    fun isAccessDisabled(server: IntegratedServer): Boolean = accessDisabled(server)

    private fun accessDisabled(server: IntegratedServer): Boolean =
        runCatching { WorldAccessFlag.isDisabled(server.getSavePath(WorldSavePath.ROOT)) }.getOrDefault(false)

    private fun runHost(server: IntegratedServer): HostResult {
        val (worldId, generation) = bumpGeneration(server)
        val controller = HostController(
            registry = Discovery.registry,
            lanOpener = MinecraftLanOpener(server),
            connectionServer = HostConnectionServer(),
            // Announce the LAN address, but best-effort open the listen port on the router via UPnP
            // so the rendezvous server's observed-public-IP endpoint is reachable across NATs. The
            // forwarding never fails the host (ForwardingEndpointResolver swallows UPnP failures).
            endpointResolver = ForwardingEndpointResolver(UpnpPortForwarder(), LocalEndpointResolver()),
            nodeId = PersistentNodeId.nodeId,
            clock = SystemClock,
            // Connected players (host + any relayed-in guests) for the world-list live badge.
            playerCount = { runCatching { server.playerManager.playerList.size }.getOrDefault(0) },
        )
        val result = runBlocking { controller.host(worldId, generation) }
        if (result is HostResult.Hosting) HostSession.install(controller) else controller.close()
        return result
    }

    /** Bump and persist the fencing generation on the server thread; return the world id + new gen. */
    private fun bumpGeneration(server: IntegratedServer): Pair<WorldId, Long> {
        val future = CompletableFuture<Pair<WorldId, Long>>()
        server.execute {
            val state = WorldIdState.get(server.overworld)
            val generation = state.incrementGeneration()
            future.complete(WorldId.of(state.worldId) to generation)
        }
        return future.get()
    }

    private fun report(result: HostResult) {
        when (result) {
            is HostResult.Hosting ->
                JukzMod.logger.info("jukz: auto-hosting {} on port {}", result.shortCode, result.port)
            is HostResult.Superseded -> {
                JukzMod.logger.info("jukz: another host already owns this world — asking the player")
                promptSuperseded(result.current)
            }
            is HostResult.Failed ->
                JukzMod.logger.warn("jukz: could not auto-host this world: {}", result.reason)
        }
    }

    /**
     * Decision 4: a rejected announce is never silent. The world already opened locally (the open
     * raced another host, or discovery was unreachable during the lookup), so the player decides:
     * keep playing the local copy (it will diverge) or leave it and join the live host as a guest.
     */
    private fun promptSuperseded(current: WorldRecord) {
        val client = MinecraftClient.getInstance()
        val shortCode = current.worldId.shortCode()
        client.execute {
            client.setScreen(
                SupersededScreen(
                    shortCode,
                    onKeepPlaying = { client.setScreen(null) },
                    onJoinInstead = { leaveAndJoin(client, current.worldId, shortCode) },
                ),
            )
        }
    }

    /** Save and leave the local copy (the vanilla quit-world sequence), then join the live host. */
    private fun leaveAndJoin(client: MinecraftClient, worldId: WorldId, shortCode: String) {
        client.world?.disconnect()
        client.disconnect(MessageScreen(Text.translatable("menu.savingLevel")))
        JoinCoordinator.start(worldId, shortCode, TitleScreen())
    }
}
