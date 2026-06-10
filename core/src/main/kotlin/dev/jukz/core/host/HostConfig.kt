package dev.jukz.core.host

/**
 * Timings for the host flow. [heartbeatIntervalMs] must sit well within the registry's record TTL
 * so a live host keeps re-announcing before its record can expire.
 */
data class HostConfig(
    val heartbeatIntervalMs: Long = 60_000,
)
