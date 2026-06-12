package dev.jukz.core.host

import dev.jukz.core.discovery.RelayOffer

/**
 * Optionally registers a relay session for a host that may be unreachable directly, returning the
 * [RelayOffer] to advertise in the discovery record (or null to advertise no relay). Called once on
 * the host path with the connection-server [listenPort] the relay must bridge guest traffic to. The
 * real implementation (fabric `WsRelayClient`) opens an outbound WebSocket control link to the
 * rendezvous relay; the default is a no-op (LAN path / tests). Best-effort: it must never throw out
 * of [register] in a way that fails hosting — return null on any failure.
 */
fun interface RelayRegistrar {
    fun register(listenPort: Int): RelayOffer?
}
