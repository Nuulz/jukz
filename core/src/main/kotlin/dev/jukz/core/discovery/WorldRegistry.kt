package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId

/** Outcome of a compare-and-set publish. */
sealed interface PublishResult {
    /** The candidate became (or stayed) the live record. */
    data class Published(val record: WorldRecord) : PublishResult

    /** Rejected: an existing record holds a token >= the candidate's. */
    data class Rejected(val current: WorldRecord) : PublishResult
}

/**
 * Abstraction over the discovery layer (the DHT). Implemented for real by a Mainline-DHT
 * adapter (BEP44 mutable items) and by [InMemoryWorldRegistry] for tests.
 *
 * The CAS semantics of [publishIfNewer] are the resource-side fence: a stale (lower) token
 * can never overwrite a fresher record, which is what prevents split-brain.
 */
interface WorldRegistry {
    /** Publish only if no live record exists, or the candidate's token is strictly greater. */
    suspend fun publishIfNewer(record: WorldRecord): PublishResult

    /**
     * Refresh an existing record without changing ownership: updates [WorldRecord.heartbeatSeq]
     * and resets the TTL, but only if [record].token equals the current record's token (same host
     * re-announcing). Returns false if the host has been superseded or the record expired — the
     * caller should then stop hosting. The same-token case is why this is separate from the strict
     * CAS [publishIfNewer].
     */
    suspend fun heartbeat(record: WorldRecord): Boolean

    /** The current live record for [worldId], or null if none / expired. */
    suspend fun lookup(worldId: WorldId): WorldRecord?

    /** Remove the record only if its token equals [token] (don't clobber a newer host). */
    suspend fun withdraw(worldId: WorldId, token: ClaimToken)
}
