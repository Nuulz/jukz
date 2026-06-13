package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.HostHandoffScreen
import dev.jukz.client.gui.NatErrorScreen
import dev.jukz.client.gui.SearchingHostScreen
import dev.jukz.client.gui.ShouldHostScreen
import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.discovery.Discovery
import dev.jukz.core.join.GameHandoff
import dev.jukz.core.join.JoinController
import dev.jukz.core.join.JoinResult
import dev.jukz.core.model.WorldId
import dev.jukz.config.JukzConfig
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectChannelDialer
import dev.jukz.transport.CompositeChannelDialer
import dev.jukz.transport.WsRelayTransport
import dev.jukz.core.util.SystemClock
import dev.jukz.sync.JGitWorldSync
import dev.jukz.sync.R2SnapshotStore
import dev.jukz.world.WorldSaveLocator
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the guest join flow on the client and the host **handoff** (F4). It runs [JoinController] off
 * the render thread, maps each [JoinResult] to a screen, and — crucially — wires the controller's
 * `onHostLost` callback. That callback fires from the live **control channel**: either the host sent a
 * `HostLeaving` notice (clean handoff, carrying a snapshot offer) or the channel broke (abrupt drop).
 * Either way we offer the takeover: pull the host's snapshot into the guest's local copy of the world
 * and open it locally, which auto-hosts and fences past the old host. No discovery, no race.
 */
object JoinCoordinator {

    fun start(
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        registry: WorldRegistry = Discovery.registry,
        dialer: ChannelDialer = defaultDialer(),
    ) {
        val client = MinecraftClient.getInstance()
        val handoff: GameHandoff = MinecraftGameHandoff { parent }
        val controller = JoinController(
            registry, dialer, handoff, SystemClock,
            onHostLost = { wid, offer, target -> onHostLeaving(client, wid, shortCode, offer, target, dialer) },
        )
        val cancelled = AtomicBoolean(false)

        val onCancel = {
            cancelled.set(true)
            controller.close()
            client.execute { client.setScreen(parent) }
        }

        client.setScreen(SearchingHostScreen(shortCode, onCancel))

        Thread {
            val result = try {
                runBlocking { controller.join(worldId) }
            } catch (e: Throwable) {
                JoinResult.Failed(e.message ?: e.toString())
            }
            if (cancelled.get()) return@Thread
            // Track the live session so leaving the world (a normal disconnect) tears the controller
            // down — otherwise its handoff watcher outlives the visit and pops a stale "Host now".
            if (result is JoinResult.Connected) GuestSession.install(controller)
            if (result is JoinResult.HostUnavailable) {
                // The rendezvous signs download URLs without checking R2 for the object (it stays
                // stateless), so a non-null sign response does NOT mean a snapshot exists. Probe the
                // tiny head object here, off the render thread: only a real 200 means a ghost is
                // present. A 404 (no upload for this code) falls through to the normal "No live host"
                // screen, instead of dangling a misleading "you have the latest world" takeover prompt.
                val ghost = R2SnapshotStore.ghostSnapshot(worldId)
                val head = ghost?.let { R2SnapshotStore.downloadText(it.headUrl) }
                if (ghost != null && head != null) {
                    client.execute { showGhostTakeover(client, worldId, shortCode, parent, ghost, head) }
                    return@Thread
                }
            }
            client.execute { applyResult(client, result, worldId, shortCode, parent) }
        }.apply {
            isDaemon = true
            name = "jukz-join"
        }.start()
    }

    /** Direct TCP, plus the relay WS when an internet rendezvous is configured (else direct-only). */
    private fun defaultDialer(): ChannelDialer {
        val url = JukzConfig.rendezvousUrl ?: return DirectChannelDialer()
        return CompositeChannelDialer(relay = WsRelayTransport(url), forceRelay = JukzConfig.forceRelay)
    }

    private fun applyResult(
        client: MinecraftClient,
        result: JoinResult,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
    ) {
        when (result) {
            is JoinResult.Connected ->
                JukzMod.logger.info("jukz: joined host at {}:{}", result.host, result.port)
            JoinResult.HostUnavailable ->
                client.setScreen(
                    ShouldHostScreen(
                        "No live host was found for $shortCode.",
                        onBack = { client.setScreen(parent) },
                    ),
                )
            // The host we tried to join was already a ghost: offer the takeover with whatever it left.
            // No live connection to ride, so the offer (from the record) is pulled directly, best-effort.
            is JoinResult.ShouldHost ->
                showHandoff(client, worldId, shortCode, parent, result.record?.snapshot, target = null, dialer = DirectChannelDialer())
            is JoinResult.Failed ->
                client.setScreen(
                    NatErrorScreen(
                        result.reason,
                        onRetry = { start(worldId, shortCode, parent) },
                        onHostLocally = { client.setScreen(parent) },
                    ),
                )
        }
    }

    /**
     * The host of a world we were connected to is leaving (control channel `HostLeaving` or break).
     * Invoked from the controller's reader thread, so it hops to the client thread to show the prompt.
     */
    private fun onHostLeaving(
        client: MinecraftClient,
        worldId: WorldId,
        shortCode: String,
        offer: SnapshotOffer?,
        target: DialTarget?,
        dialer: ChannelDialer,
    ) {
        if (!GuestSession.recentlyEngaged()) {
            // The controller's watcher outlived the visit (we left this world a while ago). Don't pop a
            // stale "Host now" at someone who has moved on — just tear the session down.
            JukzMod.logger.info("jukz: host of {} left, but we already moved on — not offering handoff", shortCode)
            GuestSession.leave()
            return
        }
        JukzMod.logger.info("jukz: host of {} is leaving (snapshot {}) — offering handoff", shortCode, if (offer != null) "offered" else "none")
        showHandoff(client, worldId, shortCode, TitleScreen(), offer, target, dialer)
    }

    private fun showHandoff(
        client: MinecraftClient,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        offer: SnapshotOffer?,
        target: DialTarget?,
        dialer: ChannelDialer,
    ) {
        // Start the snapshot download NOW, while the host is still connected — its connection server is
        // torn down within ~30 s of announcing it is leaving, so we must not wait for the user's "Host
        // now" (which can come much later) to begin the pull. The apply happens on takeover, locally.
        val prefetch = prefetchSnapshot(offer, target, dialer)
        client.execute {
            val screen = HostHandoffScreen(
                snapshotApplied = offer != null,
                onHostNow = { beginTakeover(client, worldId, shortCode, parent, prefetch) },
                onBack = { GuestSession.leave(); discardPrefetch(prefetch); client.setScreen(parent) },
            )
            // If we are still in the host's world (a live handoff), leave it cleanly WITH this prompt
            // as the screen, so the vanilla "Connection lost" never flashes. For a ghost takeover (we
            // were never connected to a world), just show it.
            if (client.world != null) client.disconnect(screen) else client.setScreen(screen)
        }
    }

    /**
     * No live host, but R2 holds a snapshot (ghost takeover). Prefetch the pack over HTTP and offer
     * the same "Host now" prompt the live handoff uses; taking over reuses [beginTakeover].
     */
    private fun showGhostTakeover(
        client: MinecraftClient,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        ghost: R2SnapshotStore.GhostUrls,
        head: String,
    ) {
        val prefetch = prefetchGhostSnapshot(ghost, head)
        val screen = HostHandoffScreen(
            snapshotApplied = true,
            onHostNow = { beginTakeover(client, worldId, shortCode, parent, prefetch) },
            onBack = { discardPrefetch(prefetch); client.setScreen(parent) },
        )
        client.setScreen(screen)
    }

    /**
     * Download the ghost pack from R2 into a temp [JGitWorldSync.Downloaded], off-thread. The [head]
     * commit id was already fetched (to confirm the ghost exists), so only the pack is pulled here.
     */
    private fun prefetchGhostSnapshot(
        ghost: R2SnapshotStore.GhostUrls,
        head: String,
    ): CompletableFuture<JGitWorldSync.Downloaded?> {
        val future = CompletableFuture<JGitWorldSync.Downloaded?>()
        Thread {
            val downloaded = runCatching {
                val pack = R2SnapshotStore.downloadToTemp(ghost.packUrl) { _, _ -> } ?: return@runCatching null
                JGitWorldSync.Downloaded(pack, org.eclipse.jgit.lib.ObjectId.fromString(head))
            }.getOrNull()
            JukzMod.logger.info("jukz: prefetched ghost snapshot: {}", if (downloaded != null) "ready" else "unavailable")
            future.complete(downloaded)
        }.apply { isDaemon = true; name = "jukz-ghost-prefetch" }.start()
        return future
    }

    /**
     * Download the offered snapshot to a temp pack off-thread, the moment the handoff is offered. When a
     * live [target] is known (the host is still connected), the pull rides that same path — crucially the
     * relay session for a relay-connected guest, so a non-reachable host's world still transfers. Without
     * a target (ghost takeover), it falls back to dialing the offer's advertised endpoint directly.
     */
    private fun prefetchSnapshot(
        offer: SnapshotOffer?,
        target: DialTarget?,
        dialer: ChannelDialer,
    ): CompletableFuture<JGitWorldSync.Downloaded?> {
        val future = CompletableFuture<JGitWorldSync.Downloaded?>()
        if (offer == null) {
            future.complete(null)
            return future
        }
        Thread {
            val sync = JGitWorldSync(dialer)
            val downloaded = runCatching {
                runBlocking {
                    if (target != null) sync.downloadSnapshot(target, offer.token) else sync.downloadSnapshot(offer)
                }
            }.getOrNull()
            JukzMod.logger.info("jukz: prefetched handoff snapshot: {}", if (downloaded != null) "ready" else "unavailable")
            future.complete(downloaded)
        }.apply { isDaemon = true; name = "jukz-snapshot-prefetch" }.start()
        return future
    }

    /** Drop a prefetched pack the user declined to take over with, off the render thread. */
    private fun discardPrefetch(prefetch: CompletableFuture<JGitWorldSync.Downloaded?>) {
        Thread {
            runCatching { prefetch.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS)?.let { Files.deleteIfExists(it.packPath) } }
        }.apply { isDaemon = true; name = "jukz-snapshot-discard" }.start()
    }

    /**
     * Take over hosting: locate our local copy of the world (or a folder to materialise it into), apply
     * the already-prefetched snapshot into it (best-effort — and required to fence past the old host's
     * generation), then open it locally bypassing discovery so it auto-hosts. Off the render thread
     * until the open. The download already ran eagerly in [showHandoff]; here we only await + apply it,
     * so a slow "Host now" click never misses the host's brief snapshot window.
     */
    private fun beginTakeover(
        client: MinecraftClient,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        prefetch: CompletableFuture<JGitWorldSync.Downloaded?>,
    ) {
        GuestSession.leave() // the old guest session is done; we are about to become the host
        client.setScreen(SearchingHostScreen(shortCode) {}) // "preparing" spinner; no cancel mid-takeover
        Thread {
            val savesDir = client.levelStorage.savesDirectory
            val existing = WorldSaveLocator.findLevelName(savesDir, worldId.uuid)
            val levelName = existing ?: "jukz-$shortCode"
            val saveDir = savesDir.resolve(levelName)

            val downloaded = runCatching { prefetch.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val applied = downloaded != null &&
                runCatching { runBlocking { JGitWorldSync().applySnapshot(saveDir, downloaded, worldId, 0L) } }.getOrDefault(false)
            runCatching { downloaded?.let { Files.deleteIfExists(it.packPath) } }

            if (existing == null && !applied) {
                JukzMod.logger.warn("jukz: no local copy of {} and no snapshot to pull; cannot take over", shortCode)
                client.execute {
                    client.setScreen(
                        ShouldHostScreen(
                            "Couldn't get a copy of $shortCode to host.",
                            onBack = { client.setScreen(parent) },
                        ),
                    )
                }
                return@Thread
            }

            JukzMod.logger.info("jukz: taking over {} (snapshot {})", shortCode, if (applied) "applied" else "unavailable")
            client.execute { WorldOpenInterceptor.openLocallyBypassingDiscovery(levelName) }
        }.apply { isDaemon = true; name = "jukz-takeover" }.start()
    }

    private const val SNAPSHOT_WAIT_MS = 30_000L
}
