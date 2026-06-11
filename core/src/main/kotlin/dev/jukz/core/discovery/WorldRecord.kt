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
 *
 * [snapshot] and [playerCount] are optional extras (wire format v3): a host shutting down may offer
 * its save (served over a SNAPSHOT channel on its own game endpoint) for a guest to take over hosting
 * (F4 handoff), and the announce carries the connected player count for the world-list live badge.
 * Both ride the LAN-multicast binary record; the rendezvous server relays neither, so over the
 * internet the offer is instead handed to connected guests in the live `HostLeaving` notice.
 */
data class WorldRecord(
    val worldId: WorldId,
    val token: ClaimToken,
    val endpoints: List<Endpoint>,
    val heartbeatSeq: Long,
    val snapshot: SnapshotOffer? = null,
    val playerCount: Int = 0,
    val relay: RelayOffer? = null,
) {
    init {
        require(endpoints.isNotEmpty()) { "a record must announce at least one endpoint" }
        require(playerCount >= 0) { "playerCount must be non-negative" }
    }

    /** Single-endpoint convenience, the common case for locally-resolved announcements. */
    constructor(worldId: WorldId, token: ClaimToken, endpoint: Endpoint, heartbeatSeq: Long) :
        this(worldId, token, listOf(endpoint), heartbeatSeq)

    /** The first (preferred) endpoint a guest should dial. */
    val primaryEndpoint: Endpoint get() = endpoints.first()

    val hostGeneration: Long get() = token.hostGeneration

    fun withHeartbeat(seq: Long): WorldRecord = copy(heartbeatSeq = seq)

    /** Attach (or clear) the snapshot offer the host serves while shutting down. */
    fun withSnapshot(offer: SnapshotOffer?): WorldRecord = copy(snapshot = offer)

    /** Attach (or clear) the relay session a non-reachable host offers as a fallback path. */
    fun withRelay(offer: RelayOffer?): WorldRecord = copy(relay = offer)
}
