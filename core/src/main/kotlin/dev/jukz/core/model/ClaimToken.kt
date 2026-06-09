package dev.jukz.core.model

/**
 * A host claim token — the fencing token of the protocol. Higher token wins.
 *
 * Compared strictly lexicographically:
 *  1. [hostGeneration] — the monotonic fence persisted in the world save, incremented
 *     on every host-open BEFORE announcing. This is the safety-critical field.
 *  2. [claimEpochMillis] — wall-clock at claim time; tiebreak only, never trusted for safety.
 *  3. [nodeId] — final deterministic tiebreak (unsigned lexicographic).
 *
 * A true tie requires identical generation, millis AND nodeId, which is impossible across
 * two distinct installs, so a unique winner always exists.
 */
data class ClaimToken(
    val hostGeneration: Long,
    val claimEpochMillis: Long,
    val nodeId: NodeId,
) : Comparable<ClaimToken> {
    init {
        require(hostGeneration >= 0) { "hostGeneration must be non-negative, was $hostGeneration" }
    }

    override fun compareTo(other: ClaimToken): Int {
        if (hostGeneration != other.hostGeneration) {
            return hostGeneration.compareTo(other.hostGeneration)
        }
        if (claimEpochMillis != other.claimEpochMillis) {
            return claimEpochMillis.compareTo(other.claimEpochMillis)
        }
        return nodeId.compareTo(other.nodeId)
    }
}
