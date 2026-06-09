package dev.jukz.core.join

import dev.jukz.core.model.WorldId

/** Terminal outcome of a [JoinController.join] attempt. Each maps to a concrete status screen. */
sealed interface JoinResult {
    /** Handshake succeeded; the relay is up and the game was handed off to [host]:[port]. */
    data class Connected(val host: String, val port: Int) : JoinResult

    /** No live record for the world — nobody is hosting it right now. */
    data object HostUnavailable : JoinResult

    /** The announced host proved to be a ghost; the caller should host the world locally. */
    data class ShouldHost(val worldId: WorldId) : JoinResult

    /** Transport or handshake error before a successful hand-off. */
    data class Failed(val reason: String) : JoinResult
}
