package dev.jukz.core.host

import dev.jukz.core.model.GameVersion

/**
 * Timings for the host flow. [heartbeatIntervalMs] must sit well within the registry's record TTL
 * so a live host keeps re-announcing before its record can expire. [settleWindowMs] is a short delay
 * before the FIRST heartbeat tick: it lets a host that raced another opener of the same world detect a
 * lost claim within seconds (the registry CAS can briefly admit two near-simultaneous claims before the
 * records propagate), instead of after a whole [heartbeatIntervalMs] — bounding any transient
 * split-brain to this window.
 */
data class HostConfig(
    val heartbeatIntervalMs: Long = 60_000,
    val settleWindowMs: Long = 2_000,
    /** This game's Minecraft version, announced so guests on other versions know before connecting. */
    val game: GameVersion? = null,
)
