package dev.jukz.core.join

/**
 * Timeouts for the join flow. The transport owns the connect timeout; these govern the jukz
 * handshake and the post-connect liveness monitor on the control channel.
 */
data class JoinConfig(
    /** Max wait for the next handshake reply before treating the host as a ghost. */
    val handshakeMs: Long = 8_000,
    /** Interval between liveness pings once connected. */
    val livenessIntervalMs: Long = 15_000,
    /** Max wait for a liveness pong before declaring the host lost. */
    val livenessTimeoutMs: Long = 20_000,
)
