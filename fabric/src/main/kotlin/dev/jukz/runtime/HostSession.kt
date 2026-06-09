package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostStatus
import kotlinx.coroutines.runBlocking

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

    /** Withdraw the discovery record and stop heartbeating. Safe to call when not hosting. */
    fun onServerStopping() {
        controller?.let {
            runCatching { it.close() }
            JukzMod.logger.info("jukz: host withdrawn on world close")
        }
        controller = null
    }
}
