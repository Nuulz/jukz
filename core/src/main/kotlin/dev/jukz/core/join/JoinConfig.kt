package dev.jukz.core.join

import dev.jukz.core.model.GameVersion

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
    /**
     * This game's Minecraft version. When set, a host on another version is refused up front
     * ([JoinResult.WrongVersion]) instead of failing mid-connection; null skips the check (tests).
     */
    val game: GameVersion? = null,
)
