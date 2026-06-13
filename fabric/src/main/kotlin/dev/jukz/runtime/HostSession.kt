package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostStatus
import dev.jukz.core.model.WorldId
import dev.jukz.sync.JGitWorldSync
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

    /** The record we are currently announcing (static info for the host UI), or null. */
    val record: WorldRecord? get() = controller?.sharedRecord

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
                runCatching { offerSnapshotForHandoff(c, saveDir) }
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
     * withdraws. Best-effort: any failure just falls through to the withdraw.
     */
    private fun offerSnapshotForHandoff(controller: HostController, saveDir: Path) {
        val pack = SnapshotPack.build(saveDir, JGitWorldSync()) ?: return
        scheduleCompaction(saveDir)
        val (offer, latch) = controller.offerSnapshot(pack.bytes, pack.head) ?: return
        JukzMod.logger.info("jukz: handing off — notifying {} guest(s) over the live connection", controller.connectedGuestCount())
        controller.notifyGuestsLeaving(offer) // push the snapshot endpoint over the live control channels
        val outcome = awaitSnapshotPull(controller, latch)
        JukzMod.logger.info("jukz: snapshot handoff {}", outcome)
    }

    /**
     * Build the world pack on a guest-less close and publish it to [GhostUpload] for the client upload
     * screen to push to R2. Local + fast (no network here); a failure clears the holder so no upload
     * screen is shown. [worldId] + [generation] come from the live `WorldIdState`, so this works even
     * when auto-hosting never finished installing a controller (a quick open->close racing the announce).
     */
    private fun armGhostUpload(saveDir: Path, worldId: WorldId, generation: Long) {
        val pack = SnapshotPack.build(saveDir, JGitWorldSync()) ?: run { GhostUpload.clear(); return }
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
    private fun awaitSnapshotPull(controller: HostController, latch: CountDownLatch): String {
        var waited = 0L
        while (waited < SNAPSHOT_WAIT_MS) {
            if (latch.await(HANDOFF_POLL_MS, TimeUnit.MILLISECONDS)) return "downloaded by a guest"
            if (controller.connectedGuestCount() == 0) return "no guest left to take over — not waiting"
            waited += HANDOFF_POLL_MS
        }
        return "timed out"
    }

    private const val SNAPSHOT_WAIT_MS = 30_000L
    private const val HANDOFF_POLL_MS = 250L
}
