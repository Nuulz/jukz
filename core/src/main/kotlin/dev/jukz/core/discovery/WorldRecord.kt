package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId

/**
 * The value the current host publishes into the DHT for a [worldId]. Maps directly onto a
 * BEP44 mutable item (well under the 1000-byte limit). [heartbeatSeq] advances on each
 * re-announce so guests can tell a live host from a ghost.
 */
data class WorldRecord(
    val worldId: WorldId,
    val token: ClaimToken,
    val endpoint: Endpoint,
    val heartbeatSeq: Long,
) {
    val hostGeneration: Long get() = token.hostGeneration

    fun withHeartbeat(seq: Long): WorldRecord = copy(heartbeatSeq = seq)
}
