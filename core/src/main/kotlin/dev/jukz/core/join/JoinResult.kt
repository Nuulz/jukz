package dev.jukz.core.join

import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.WorldId

/** Terminal outcome of a [JoinController.join] attempt. Each maps to a concrete status screen. */
sealed interface JoinResult {
    /** Handshake succeeded; the relay is up and the game was handed off to [host]:[port]. */
    data class Connected(val host: String, val port: Int) : JoinResult

    /** No live record for the world — nobody is hosting it right now. */
    data object HostUnavailable : JoinResult

    /**
     * The announced host proved to be a ghost; the caller should host the world locally. [record] is
     * the discovery record that was in flight (it may carry a [WorldRecord.snapshot] offer the host
     * left behind), so the caller can pull the latest save before taking over hosting (F4-B).
     */
    data class ShouldHost(val worldId: WorldId, val record: WorldRecord? = null) : JoinResult

    /** Transport or handshake error before a successful hand-off. */
    data class Failed(val reason: String) : JoinResult
}
