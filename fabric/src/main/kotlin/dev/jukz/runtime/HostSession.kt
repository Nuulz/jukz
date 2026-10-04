package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostStatus
import dev.jukz.core.model.WorldId
import dev.jukz.sync.JGitWorldSync
import dev.jukz.net.WorldAccessPayload
import dev.jukz.world.WorldKeyStore
import dev.jukz.sync.SnapshotCodec
import dev.jukz.sync.SnapshotPack
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Holds the live host session — the `core` [HostController] driving publish + heartbeat — so the
 * client-side share action can start it, the host-info screen can read it, and the server lifecycle
 * can withdraw it. Common-safe: it touches only `core` types, never client-only Minecraft classes,
 * so it also loads on a dedicated server. `dev.jukz.client.ShareCoordinator` installs the controller
 * once a world is shared; [JukzMod] calls [onServerStopping] to tear it down when the world closes.
 */
object HostSession {

    @Volatile
    private var controller: HostController? = null

    @Volatile
    private var onWithdraw: () -> Unit = {}

    // Set true by SERVER_STOPPING so that a late-finishing announce thread does not install a
    // controller whose game server is already gone. Reset by SERVER_STARTING for the next world.
    @Volatile
    private var serverStopped = false

    val isHosting: Boolean get() = controller != null

    /** Guests connected over a live control channel right now (0 when not hosting). */
    fun connectedGuestCount(): Int = controller?.connectedGuestCount() ?: 0

    /** Tell connected guests we closed access (no handoff), before withdrawing and kicking them. */
    fun notifyGuestsClosed() {
        controller?.let { runCatching { it.notifyGuestsClosed() } }
    }

    /** The record we are currently announcing (static info for the host UI), or null. */
    val record: WorldRecord? get() = controller?.sharedRecord

    /**
     * What a player who joins the hosted world receives in-game: the world key and this session's
     * handoff gate (see [WorldAccessPayload]). Null when not hosting or the key isn't loaded.
     */
    fun accessPayload(): WorldAccessPayload? {
        val c = controller ?: return null
        val worldId = c.sharedRecord?.worldId ?: return null
        val key = WorldKeyStore.keyFor(worldId) ?: return null
        return WorldAccessPayload(worldId.uuid, key.encode(), c.handoffGate)
    }

    /** Reset the stopped flag at the start of each new world so the next announce can install. */
    fun onServerStarting() {
        serverStopped = false
    }

    /**
     * Signal that the integrated server is actually stopping. Called from [JukzMod] before
     * [onServerStopping] so that a background announce thread racing SERVER_STOPPING does not
     * install a controller whose game server port is already dead.
     */
    fun markServerStopped() {
        serverStopped = true
    }

    /**
     * Record a freshly-started host controller. [onWithdraw] is an optional teardown hook run when the
     * session stops (e.g. closing the relay control link), kept as a plain lambda so this holder stays
     * free of fabric/transport types.
     *
     * If the integrated server already stopped while the announce was in flight, close the controller
     * immediately instead of installing it — the game port is dead and guests would get "Disconnected".
     */
    fun install(controller: HostController, onWithdraw: () -> Unit = {}) {
        if (serverStopped) {
            JukzMod.logger.info("jukz: server stopped during announce — withdrawing controller immediately")
            runCatching { controller.close() }
            runCatching { onWithdraw() }
            return
        }
        this.controller = controller
        this.onWithdraw = onWithdraw
    }

    /** Live ownership/heartbeat snapshot; blocks briefly on the registry, so call off the render thread. */
    fun currentStatus(): HostStatus? = controller?.let { runBlocking { it.status() } }

    /**
     * Stop hosting because a newer host superseded us — NOT a clean world close. Closes the controller
     * (which withdraws our record via the token CAS, so the winner is never clobbered) and clears the
     * session. Deliberately does NO handoff and NO ghost upload: our copy is a losing fork, not the
     * canonical world, so backing it up would only re-pollute discovery/R2 with a divergent snapshot
     * (the seed of the 2026-06-13 incident). Safe to call when not hosting. The caller (HostCoordinator)
     * then offers the player the live winner via [dev.jukz.client.HostCoordinator] supersession prompt.
     */
    fun stopHostingSuperseded() {
        val c = controller ?: return
        runCatching { c.close() } // withdraw (CAS on our own token) + stop heartbeat + close the server
        runCatching { onWithdraw() } // tear down any relay control link
        controller = null
        onWithdraw = {}
        JukzMod.logger.info("jukz: stopped hosting — superseded by a newer host")
    }

    /**
     * Withdraw the discovery record and stop heartbeating. Safe to call when not hosting. When a guest
     * is connected over a live control channel and [saveDir] is known, first hand the world off (F4): we
     * arm the snapshot and push a `HostLeaving` notice (with the snapshot endpoint) to each connected
     * guest over the connection that is still open, then wait briefly for a download before withdrawing.
     * This uses the open connection rather than discovery, so it never races the registry/cache.
     */
    fun onServerStopping(
        saveDir: Path? = null,
        worldId: WorldId? = null,
        generation: Long = 0L,
        flushSave: () -> Unit = {},
    ) {
        val c = controller
        if (saveDir != null) {
            if (c != null && c.connectedGuestCount() > 0) {
                runCatching { flushSave() } // force the world to disk first so the snapshot is current
                val handedOff = runCatching { offerSnapshotForHandoff(c, saveDir) }.getOrDefault(false)
                // A handoff that no guest completed (they all dropped their control channel, or it timed
                // out) would otherwise strand the latest world on our local disk. The live handoff is an
                // optimization; the cloud ghost is the durability net — so when nobody took over, back
                // the world up to R2 instead, exactly as a guest-less close does.
                if (!handedOff && worldId != null && GhostUpload.isArmed()) {
                    runCatching { armGhostUpload(saveDir, worldId, generation) }
                }
            } else if (worldId != null && GhostUpload.isArmed()) {
                runCatching { flushSave() }
                runCatching { armGhostUpload(saveDir, worldId, generation) }
            }
        }
        if (c != null) {
            runCatching { c.close() } // withdraw + stop heartbeating
            runCatching { onWithdraw() } // tear down any relay control link
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
        onWithdraw = {}
    }

    /**
     * Build the save pack and arm the connection server, push the offer to connected guests over their
     * live control channels, then block up to [SNAPSHOT_WAIT_MS] for a guest to pull. The pull rides
     * the same connection-server port the game uses, so it crosses NAT exactly like play does — no
     * second port to forward. The armed server stays open until the caller's [HostController.close]
     * withdraws. Returns true only when a guest actually pulled the snapshot (and so became the new
     * host); false on any failure or when nobody took over, so the caller can fall back to a cloud
     * backup rather than strand the world.
     */
    private fun offerSnapshotForHandoff(controller: HostController, saveDir: Path): Boolean {
        val pack = SnapshotPack.build(saveDir, JGitWorldSync(), SnapshotCodec.Level.FAST) ?: return false // the next host is waiting
        scheduleCompaction(saveDir)
        val (offer, latch) = controller.offerSnapshot(pack.bytes, pack.head) ?: return false
        JukzMod.logger.info("jukz: handing off — notifying {} guest(s) over the live connection", controller.connectedGuestCount())
        controller.notifyGuestsLeaving(offer) // push the snapshot endpoint over the live control channels
        val outcome = awaitSnapshotPull(controller, latch)
        JukzMod.logger.info("jukz: snapshot handoff {}", outcome.log)
        return outcome.downloaded
    }

    /**
     * Build the world pack on a guest-less close and publish it to [GhostUpload] for the client upload
     * screen to push to R2. Local + fast (no network here); a failure clears the holder so no upload
     * screen is shown. [worldId] + [generation] come from the live `WorldIdState`, so this works even
     * when auto-hosting never finished installing a controller (a quick open->close racing the announce).
     */
    private fun armGhostUpload(saveDir: Path, worldId: WorldId, generation: Long) {
        WorldKeyStore.loadOrCreate(saveDir, worldId) // the upload is signed (auto-host may not have run)
        val pack = SnapshotPack.build(saveDir, JGitWorldSync(), SnapshotCodec.Level.SMALL) ?: run { GhostUpload.clear(); return }
        GhostUpload.arm(GhostUpload.Pending(worldId, generation, pack.bytes, pack.head))
        JukzMod.logger.info("jukz: armed ghost snapshot ({} bytes) for upload", pack.bytes.size)
        scheduleCompaction(saveDir)
    }

    /**
     * Reclaim the host's local `.git` off the hot path: the snapshot build just re-rooted HEAD, so the
     * old history is unreachable and collectable. Runs on a daemon thread so it never delays the world
     * close, and is gated + grace-guarded inside [JGitWorldSync.compactIfNeeded] so it is safe to fire
     * even if the player immediately reopens the world.
     */
    private fun scheduleCompaction(saveDir: Path) {
        Thread { JGitWorldSync().compactIfNeeded(saveDir) }
            .apply { isDaemon = true; name = "jukz-repo-compact" }
            .start()
    }

    /**
     * Wait for a guest to pull the handoff snapshot, but stop the instant every guest has disconnected.
     * If the receiver leaves or closes the game there is nobody left to take over, so blocking the full
     * [SNAPSHOT_WAIT_MS] would just freeze the leaving host on its non-interactive "handing off" screen
     * for no reason. We poll the latch in short slices and bail out the moment the connected-guest count
     * hits zero (the receiver dropped its control channel). Returns a log-ready outcome string.
     */
    private fun awaitSnapshotPull(controller: HostController, latch: CountDownLatch): HandoffOutcome {
        var waited = 0L
        while (waited < SNAPSHOT_WAIT_MS) {
            if (latch.await(HANDOFF_POLL_MS, TimeUnit.MILLISECONDS)) return HandoffOutcome.DOWNLOADED
            if (controller.connectedGuestCount() == 0) return HandoffOutcome.NO_GUEST_LEFT
            waited += HANDOFF_POLL_MS
        }
        return HandoffOutcome.TIMED_OUT
    }

    /**
     * The result of awaiting a handoff pull: a [log] string for the operator line plus the one fact the
     * caller acts on — did a guest actually take the world ([downloaded])? When none did, the leaving
     * host backs the world up to the cloud rather than strand the latest state on local disk.
     */
    private enum class HandoffOutcome(val log: String, val downloaded: Boolean) {
        DOWNLOADED("downloaded by a guest", true),
        NO_GUEST_LEFT("no guest left to take over — not waiting", false),
        TIMED_OUT("timed out", false),
    }

    private const val SNAPSHOT_WAIT_MS = 30_000L
    private const val HANDOFF_POLL_MS = 250L
}
