package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId

/**
 * The value the current host publishes into discovery for a [worldId]. Maps directly onto a
 * BEP44 mutable item (well under the 1000-byte limit). [heartbeatSeq] advances on each
 * re-announce so guests can tell a live host from a ghost.
 *
 * [endpoints] is an ordered candidate list — a guest dials them in order until one connects.
 * The host announces what it can resolve locally (LAN address today); the rendezvous server
 * appends the announcer's observed public address, which is how a record becomes reachable
 * across NATs without the client needing STUN.
 */
data class WorldRecord(
    val worldId: WorldId,
    val token: ClaimToken,
    val endpoints: List<Endpoint>,
    val heartbeatSeq: Long,
) {
    init {
        require(endpoints.isNotEmpty()) { "a record must announce at least one endpoint" }
    }

    /** Single-endpoint convenience, the common case for locally-resolved announcements. */
    constructor(worldId: WorldId, token: ClaimToken, endpoint: Endpoint, heartbeatSeq: Long) :
        this(worldId, token, listOf(endpoint), heartbeatSeq)

    /** The first (preferred) endpoint a guest should dial. */
    val primaryEndpoint: Endpoint get() = endpoints.first()

    val hostGeneration: Long get() = token.hostGeneration

    fun withHeartbeat(seq: Long): WorldRecord = copy(heartbeatSeq = seq)
}
