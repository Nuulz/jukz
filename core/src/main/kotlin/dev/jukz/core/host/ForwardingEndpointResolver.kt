package dev.jukz.core.host

import dev.jukz.core.model.Endpoint

/**
 * An [EndpointResolver] that first asks a [PortForwarder] to open the port on the gateway, then
 * returns the [delegate] resolver's address. The forwarding is strictly best-effort: any failure
 * (no gateway, UPnP disabled, an exception) is swallowed so the host still publishes — same-network
 * play keeps working and, when a rendezvous server is in use, it appends the observed public IP with
 * this same port, which the forwarder has now (best-effort) opened for cross-NAT guests.
 *
 * This keeps the correctness-critical invariant — *forwarding must never fail the host* — in the
 * tested core, leaving only the real UPnP/SSDP/SOAP calls in the flagged fabric adapter.
 */
class ForwardingEndpointResolver(
    private val forwarder: PortForwarder,
    private val delegate: EndpointResolver,
) : EndpointResolver {

    override fun resolve(port: Int): Endpoint {
        runCatching { forwarder.open(port) } // best-effort: a missing/failed gateway must not fail the host
        return delegate.resolve(port)
    }
}
