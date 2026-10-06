package dev.jukz.transport

import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.Endpoint
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Real-today [EndpointResolver]: returns the host's LAN address for the bound port. This is reachable
 * on a local network but NOT across NATs — cross-country reachability needs [StunEndpointResolver]
 * (flagged). Falls back to loopback if the LAN address can't be determined.
 *
 * [resolveAll] also lists the host's global IPv6 addresses: IPv6 has no NAT, so a guest with IPv6
 * reaches them directly (if the host's firewall lets the port in), before falling back to the relay.
 */
class LocalEndpointResolver(
    private val globalIpv6: () -> List<String> = ::globalIpv6Addresses,
) : EndpointResolver {
    override fun resolve(port: Int): Endpoint {
        val host = runCatching { InetAddress.getLocalHost().hostAddress }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "127.0.0.1"
        return Endpoint(host, port)
    }

    override fun resolveAll(port: Int): List<Endpoint> =
        listOf(resolve(port)) + runCatching { globalIpv6() }.getOrDefault(emptyList()).take(MAX_IPV6).map { Endpoint(it, port) }

    companion object {
        /** Two is plenty: the stable address plus one privacy address; each dead one costs a guest a timeout. */
        const val MAX_IPV6 = 2

        /** Global unicast IPv6 (2000::/3) on interfaces that are up, not loopback/virtual; no zone id. */
        fun globalIpv6Addresses(): List<String> =
            NetworkInterface.networkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet6Address>()
                .filter { isGlobal(it) }
                .map { it.hostAddress.substringBefore('%') }
                .distinct()

        fun isGlobal(a: Inet6Address): Boolean {
            val first = a.address[0].toInt() and 0xff
            return first and 0xe0 == 0x20 && !a.isLinkLocalAddress && !a.isSiteLocalAddress
        }
    }
}
