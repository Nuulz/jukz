package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.host.HostController

/**
 * Holds the live host session — the `core` [HostController] driving publish + heartbeat — so the
 * client-side share action can start it and the server lifecycle can withdraw it. Common-safe: it
 * touches only `core` types, never client-only Minecraft classes, so it also loads on a dedicated
 * server. The client-side `dev.jukz.client.ShareCoordinator` installs the controller here once a
 * world is shared; [JukzMod] calls [onServerStopping] to tear it down when the world closes.
 */
object HostSession {

    /** What the share screen needs to re-display the code without re-opening the world. */
    data class ShareInfo(val shortCode: String, val port: Int)

    @Volatile
    private var controller: HostController? = null

    @Volatile
    var shareInfo: ShareInfo? = null
        private set

    val isHosting: Boolean get() = controller != null

    /** Record a freshly-started host controller and the info needed to re-show its share code. */
    fun install(controller: HostController, info: ShareInfo) {
        this.controller = controller
        this.shareInfo = info
    }

    /** Withdraw the discovery record and stop heartbeating. Safe to call when not hosting. */
    fun onServerStopping() {
        controller?.let {
            runCatching { it.close() }
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
        shareInfo = null
    }
}
