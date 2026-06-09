package dev.jukz.core.election

/** Timeout windows for host election. Defaults come from the design spec (§4.4). */
data class ElectionConfig(
    /** R2: wait after announcing before accepting guests, to cover DHT propagation lag. */
    val announceSettleWindowMs: Long = 2_000,
    /** Max time to resolve a contested claim before confirming or yielding. */
    val claimDuelTimeoutMs: Long = 3_000,
    /** Host re-announce + heartbeatSeq bump interval. */
    val heartbeatIntervalMs: Long = 5_000,
    /** R10/R11: host presumed dead if heartbeatSeq does not advance within this window (3x). */
    val livenessTimeoutMs: Long = 15_000,
    /** Transport connect timeout to an announced endpoint. */
    val connectAttemptTimeoutMs: Long = 4_000,
    /** DHT record TTL ceiling so abandoned worlds drop fast. */
    val announceTtlMs: Long = 30 * 60 * 1000L,
    /** Bound on duel rounds (fence-and-retry) before giving up. */
    val maxDuelRounds: Int = 5,
)
