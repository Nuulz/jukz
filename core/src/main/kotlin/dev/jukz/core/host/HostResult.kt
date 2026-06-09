package dev.jukz.core.host

import dev.jukz.core.discovery.WorldRecord

/** Terminal outcome of a [HostController.host] attempt. Each maps to a concrete status screen. */
sealed interface HostResult {
    /** Port open, discovery record published, heartbeat running. [shortCode] is the share code. */
    data class Hosting(val shortCode: String, val port: Int) : HostResult

    /** A live record already holds a token >= ours; another host owns this world. Not overwritten. */
    data class Superseded(val current: WorldRecord) : HostResult

    /** Could not open the local port, or resolving/publishing the endpoint threw. */
    data class Failed(val reason: String) : HostResult
}
