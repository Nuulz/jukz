package dev.jukz.transport

import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.Endpoint

/**
 * Public, cross-NAT [EndpointResolver] (FLAGGED — requires live-network testing).
 *
 * Real implementation (spec §4.3): obtain the server-reflexive address with a STUN binding request
 * ([dev.jukz.net.StunClient] already provides this), then publish a reachable mapping — a UPnP IGD
 * port-map ([dev.jukz.net.UpnpMapper]) or, when that fails, a TURN/relay allocation. The resolved
 * [Endpoint] is what guests dial; [dev.jukz.transport.IceTransport] hole-punches to it. Swapping
 * this in for [LocalEndpointResolver] is what turns a LAN share into a cross-country one.
 */
class StunEndpointResolver : EndpointResolver {
    override fun resolve(port: Int): Endpoint =
        throw NotImplementedError(
            "StunEndpointResolver requires STUN reflexive address + UPnP/TURN mapping and live-network testing",
        )
}
