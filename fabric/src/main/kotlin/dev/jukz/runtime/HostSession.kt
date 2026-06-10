package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostStatus
import dev.jukz.sync.JGitWorldSync
import dev.jukz.sync.SnapshotServer
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

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

    val isHosting: Boolean get() = controller != null

    /** The record we are currently announcing (static info for the host UI), or null. */
    val record: WorldRecord? get() = controller?.sharedRecord

    /** Record a freshly-started host controller. */
    fun install(controller: HostController) {
        this.controller = controller
    }

    /** Live ownership/heartbeat snapshot; blocks briefly on the registry, so call off the render thread. */
    fun currentStatus(): HostStatus? = controller?.let { runBlocking { it.status() } }

    /**
     * Withdraw the discovery record and stop heartbeating. Safe to call when not hosting. When a guest
     * is connected over a live control channel and [saveDir] is known, first hand the world off (F4): we
     * serve a snapshot and push a `HostLeaving` notice (with the snapshot URL) to each connected guest
     * over the connection that is still open, then wait briefly for a download before withdrawing. This
     * uses the open connection rather than discovery, so it never races the registry/cache.
     */
    fun onServerStopping(saveDir: Path? = null, flushSave: () -> Unit = {}) {
        controller?.let { c ->
            if (saveDir != null && c.connectedGuestCount() > 0) {
                runCatching { flushSave() } // force the world to disk first so the snapshot is current
                runCatching { offerSnapshotForHandoff(c, saveDir) }
            }
            runCatching { c.close() } // withdraw + stop heartbeating
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
    }

    /**
     * Serve the save over HTTP, push the offer to connected guests over their live control channels,
     * then block up to [SNAPSHOT_WAIT_MS] for a guest to download. Best-effort: any failure just falls
     * through to the withdraw.
     */
    private fun offerSnapshotForHandoff(controller: HostController, saveDir: Path) {
        val current = controller.sharedRecord ?: return
        val host = current.primaryEndpoint.host
        val server = SnapshotServer.serve(saveDir, JGitWorldSync(), host) ?: return
        JukzMod.logger.info(
            "jukz: handing off — notifying {} guest(s), snapshot at {}",
            controller.connectedGuestCount(), server.offer.url(),
        )
        try {
            controller.notifyGuestsLeaving(server.offer) // push over the live control channels
            val pulled = server.awaitDownload(SNAPSHOT_WAIT_MS)
            JukzMod.logger.info("jukz: snapshot handoff {}", if (pulled) "downloaded by a guest" else "timed out")
        } finally {
            server.close()
        }
    }

    private const val SNAPSHOT_WAIT_MS = 30_000L
}
