package dev.jukz.transport

import dev.jukz.JukzMod
import dev.jukz.core.host.PortForwarder
import dev.jukz.net.UpnpMapper

/**
 * Real [PortForwarder]: asks the home router to open [port] via UPnP IGD ([UpnpMapper]) so the
 * rendezvous server's observed-public-IP endpoint becomes reachable from other networks — this is
 * the half that turns a LAN share into a cross-country one. The mapping is 1:1 (external port ==
 * internal [port]), matching the port the rendezvous server reuses for the observed public address,
 * and [dev.jukz.core.host.HostConnectionServer] binds `0.0.0.0`, so forwarded traffic reaches it.
 *
 * Best-effort and non-fatal by contract — [dev.jukz.core.host.ForwardingEndpointResolver] swallows
 * any failure, so a router without UPnP only means internet guests need a manual port-forward; LAN
 * play is unaffected. The router's reported external IP is not announced (it is wrong under CGNAT);
 * the address comes from the rendezvous server's observed IP, this forwarder only opens the path.
 *
 * FLAGGED: issues real SSDP/SOAP to whatever gateway is on the LAN; needs live-network testing.
 */
class UpnpPortForwarder(private val upnp: UpnpMapper = UpnpMapper()) : PortForwarder {

    override fun open(port: Int): String? {
        val mapping = upnp.mapPort(port)
        if (mapping == null) {
            JukzMod.logger.info(
                "jukz: no UPnP gateway opened port {} — LAN play works; internet guests need a manual {}/TCP forward",
                port, port,
            )
            return null
        }
        JukzMod.logger.info(
            "jukz: UPnP opened port {} on the router (public IP {}) — internet guests can join",
            port, mapping.externalIp,
        )
        return "${mapping.externalIp}:${mapping.externalPort}"
    }
}
