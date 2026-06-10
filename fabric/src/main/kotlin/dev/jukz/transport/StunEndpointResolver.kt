package dev.jukz.transport

import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.Endpoint
import dev.jukz.net.StunClient
import dev.jukz.net.UpnpMapper

/**
 * Public, cross-NAT [EndpointResolver] for a serverless internet path (no rendezvous to observe the
 * public IP). It makes the host's TCP listener reachable from another network:
 *  1. UPnP IGD ([UpnpMapper]) — map the TCP port and read the router's public IP. When the router
 *     supports it (common at home), this yields a directly-reachable `publicIp:port`.
 *  2. STUN fallback ([StunClient]) — learn the public IP; the port must then be forwarded manually.
 *
 * FLAGGED: both rungs touch real network gear (an IGD / a STUN server), so this needs live-network
 * testing on real NATs. The LAN path uses [LocalEndpointResolver] instead, where the LAN address is
 * directly dialable. Hole-punch / TURN relay (for symmetric NATs with no UPnP) remain in
 * [dev.jukz.transport.IceTransport], still to be wired.
 */
class StunEndpointResolver(
    private val upnp: UpnpMapper = UpnpMapper(),
    private val stun: StunClient = StunClient(),
) : EndpointResolver {

    override fun resolve(port: Int): Endpoint {
        upnp.mapPort(port)?.let { return Endpoint(it.externalIp, it.externalPort) }
        stun.discover()?.let { return Endpoint(it.host, port) }
        throw IllegalStateException("no public endpoint: UPnP IGD unavailable and STUN unreachable")
    }
}
