package dev.jukz.core.discovery

/**
 * The host's offer of a one-shot world snapshot so a guest taking over hosting (F4 handoff) can pull
 * the latest save before becoming host. [host]/[port] point at the host's connection-server endpoint —
 * the SAME port guests already reach for play — and the guest pulls over a
 * [dev.jukz.core.transport.ConnectionType.SNAPSHOT] channel there, so the transfer rides the one NAT
 * traversal that already works (no second port to forward). [token] gates the download: the host
 * serves only a guest presenting this exact token (handed to it over the live control channel in the
 * [dev.jukz.core.handshake.Message.HostLeaving] notice), and rejects any other.
 *
 * It is delivered to connected guests in the [dev.jukz.core.handshake.Message.HostLeaving] message over
 * the open control channel — never the registry — so it survives the cross-internet path even though
 * the rendezvous server does not relay it. It also rides the LAN-multicast binary record for the
 * ghost-takeover path (a guest that looks the world up after the host is already gone).
 */
data class SnapshotOffer(val host: String, val port: Int, val token: String) {
    init {
        require(host.isNotBlank()) { "snapshot host must not be blank" }
        require(port in 1..65535) { "snapshot port out of range: $port" }
        require(token.isNotBlank()) { "snapshot token must not be blank" }
    }
}
