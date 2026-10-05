package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.SupersededScreen
import dev.jukz.config.JukzConfig
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
import dev.jukz.transport.RecordingPortForwarder
import dev.jukz.transport.UpnpPortForwarder
import dev.jukz.transport.WsRelayClient
import dev.jukz.world.WorldAccessFlag
import dev.jukz.world.WorldKeyStore
import dev.jukz.world.WorldIdSidecar
import dev.jukz.world.WorldIdState
import kotlinx.coroutines.runBlocking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.server.IntegratedServer
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
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

    /** How long [disableAccess] waits after pushing `HostClosed` before withdrawing and kicking. */
    private const val NOTICE_GRACE_MS = 300L

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
        WorldAccessFlag.disable(server.getWorldPath(LevelResource.ROOT))
        Thread {
            // Tell guests first, over their live control channels: without this the withdraw + kick read
            // as an abrupt host drop, and a guest would offer to host its stale copy beside ours (a split).
            HostSession.notifyGuestsClosed()
            Thread.sleep(NOTICE_GRACE_MS) // let the notice land before the channels close
            HostSession.onServerStopping() // withdraw from discovery (no snapshot — the world stays open locally)
            val message = Component.literal("The host has closed access to this world.")
            server.execute {
                val kicked = server.playerList.players.toList()
                    .filterNot { server.isSingleplayerOwner(it.gameProfile) }
                kicked.forEach { it.connection.disconnect(message) }
                JukzMod.logger.info("jukz: access closed; {} guest(s) disconnected", kicked.size)
            }
        }.apply { isDaemon = true; name = "jukz-access-close" }.start()
    }

    /** Re-open access to this world (F4-D): drop the flag and run the normal announce flow again. */
    fun enableAccess(server: IntegratedServer) {
        WorldAccessFlag.enable(server.getWorldPath(LevelResource.ROOT))
        autoHost(server)
    }

    fun isAccessDisabled(server: IntegratedServer): Boolean = accessDisabled(server)

    private fun accessDisabled(server: IntegratedServer): Boolean =
        runCatching { WorldAccessFlag.isDisabled(server.getWorldPath(LevelResource.ROOT)) }.getOrDefault(false)

    private fun runHost(server: IntegratedServer): HostResult {
        val (worldId, generation) = bumpGeneration(server)
        // Every announce, heartbeat, withdraw and cloud upload for this world is signed with its key.
        WorldKeyStore.loadOrCreate(server.getWorldPath(LevelResource.ROOT), worldId)
        // Share one forwarder between the resolver (which attempts the UPnP map) and the relay
        // registrar (which only registers a relay session when that map failed — CGNAT / no IGD).
        val forwarder = RecordingPortForwarder(UpnpPortForwarder())
        // Register a relay session when UPnP could not open the port, or always under the force-relay
        // dev toggle (so the relay path can be exercised even on a reachable host).
        val relayClient = WsRelayClient(JukzConfig.rendezvousUrl, shouldRegister = { JukzConfig.forceRelay || forwarder.upnpFailed() })
        val controller = HostController(
            registry = Discovery.registry,
            lanOpener = MinecraftLanOpener(server),
            connectionServer = HostConnectionServer(),
            // Announce the LAN address, but best-effort open the listen port on the router via UPnP
            // so the rendezvous server's observed-public-IP endpoint is reachable across NATs. The
            // forwarding never fails the host (ForwardingEndpointResolver swallows UPnP failures).
            endpointResolver = ForwardingEndpointResolver(forwarder, LocalEndpointResolver()),
            nodeId = PersistentNodeId.nodeId,
            clock = SystemClock,
            // Self-heal: if our lease is genuinely lost to a newer host (the heartbeat CAS fails),
            // stop serving our fork and offer the player the live winner — never keep a silent second
            // server running (the 2026-06-13 split-brain, where this callback was unwired and a
            // superseded host served + LAN-announced forever).
            onHostLost = { lostWorldId -> onAutoHostLost(lostWorldId) },
            // Connected players (host + any relayed-in guests) for the world-list live badge.
            playerCount = { runCatching { server.playerList.players.size }.getOrDefault(0) },
            // When UPnP could not open the port, register a relay session so non-reachable guests
            // (CGNAT, no UPnP) can still connect; the offer rides the announced record.
            relayRegistrar = relayClient,
        )
        val result = runBlocking { controller.host(worldId, generation) }
        if (result is HostResult.Hosting) {
            HostSession.install(controller) { relayClient.close() }
            server.execute { JukzMod.broadcastWorldAccess(server) } // players already in get the new gate
        } else {
            relayClient.close()
            controller.close()
        }
        return result
    }

    /** Bump and persist the fencing generation on the server thread; return the world id + new gen. */
    private fun bumpGeneration(server: IntegratedServer): Pair<WorldId, Long> {
        val future = CompletableFuture<Pair<WorldId, Long>>()
        server.execute {
            val state = WorldIdState.get(server.overworld())
            val generation = state.incrementGeneration()
            // Keep jukz.dat in step: it is what's read while the world is closed (menu uploads, the
            // "newer cloud copy?" check on open). It used to keep the pre-bump value, one behind.
            runCatching { WorldIdSidecar.write(server, state) }
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
     * The auto-host lost its lease *after* it had been serving: a newer host genuinely superseded us
     * (the heartbeat CAS failed — see [dev.jukz.discovery.CompositeWorldRegistry.heartbeat], which now
     * reports a real LAN supersession even when the rendezvous is optimistically up). Stop serving our
     * losing fork at once (withdraw + close, no handoff, no ghost backup) and offer the player the live
     * winner, instead of silently running a divergent second copy — the 2026-06-13 split-brain, where a
     * superseded host kept serving and LAN-announcing forever. Fired off the heartbeat coroutine, so the
     * registry lookup + teardown run on a daemon thread and the UI hops back via [promptSuperseded].
     */
    private fun onAutoHostLost(worldId: WorldId) {
        if (!HostSession.isHosting) return // already torn down (the world is closing) — nothing to recover
        Thread {
            JukzMod.logger.info("jukz: lost the host lease for {} — a newer host took over", worldId.shortCode())
            val winner = runCatching { runBlocking { Discovery.registry.lookup(worldId) } }.getOrNull()
            HostSession.stopHostingSuperseded()
            if (winner != null) promptSuperseded(winner)
        }.apply { isDaemon = true; name = "jukz-superseded" }.start()
    }

    /**
     * Decision 4: a rejected announce is never silent. The world already opened locally (the open
     * raced another host, or discovery was unreachable during the lookup), so the player decides:
     * keep playing the local copy (it will diverge) or leave it and join the live host as a guest.
     */
    private fun promptSuperseded(current: WorldRecord) {
        val client = Minecraft.getInstance()
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
    private fun leaveAndJoin(client: Minecraft, worldId: WorldId, shortCode: String) {
        client.level?.disconnect()
        client.disconnect(GenericMessageScreen(Component.translatable("menu.savingLevel")))
        JoinCoordinator.start(worldId, shortCode, TitleScreen())
    }

    /**
     * Is this world eligible to be backed up to the cloud when it closes? True when a rendezvous (hence
     * an R2 signer) is configured and access is not closed. Read by [JukzMod] at SERVER_STOPPING (which
     * only fires for a locally-opened integrated world, so this is never a remote guest) to arm
     * [dev.jukz.runtime.GhostUpload] before the teardown hook.
     *
     * Deliberately does NOT require the absence of guests: a host that leaves with a guest connected
     * prefers a live P2P handoff, but [dev.jukz.runtime.HostSession.onServerStopping] falls back to this
     * cloud backup when the handoff reaches nobody, so the latest state is never stranded on local disk.
     *
     * NB: deliberately does NOT require [HostSession.isHosting]. Auto-hosting can still be establishing
     * (the announce/relay round-trip takes a moment) when a player opens a world and closes it again
     * right away; gating on the live controller would skip the backup for that quick close. The world
     * is ours regardless — its id and generation come from the live [WorldIdState], not the controller.
     */
    fun shouldUploadGhost(saveDir: Path): Boolean =
        dev.jukz.sync.R2SnapshotStore.isConfigured() &&
            !runCatching { WorldAccessFlag.isDisabled(saveDir) }.getOrDefault(false)
}
