package dev.jukz.core.discovery

/**
 * The host's offer of a relay session so a guest that cannot reach the host directly (no UPnP,
 * CGNAT, symmetric NAT) can connect through the rendezvous relay instead. [sessionId] is a
 * high-entropy bearer capability the host registered on the relay (`/v1/relay/host`); a guest
 * dials `/v1/relay/connect?session=<sessionId>` and the relay splices the two streams. It rides
 * the discovery record (wire v4) alongside the direct endpoints; the guest tries the direct
 * endpoints first and falls back to this only when all of them fail.
 */
data class RelayOffer(val sessionId: String) {
    init {
        require(sessionId.isNotBlank()) { "relay session id must not be blank" }
    }
}
