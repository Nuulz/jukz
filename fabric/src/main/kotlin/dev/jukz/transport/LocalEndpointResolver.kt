package dev.jukz.transport

import dev.jukz.core.host.EndpointResolver
import dev.jukz.core.model.Endpoint
import java.net.InetAddress

/**
 * Real-today [EndpointResolver]: returns the host's LAN address for the bound port. This is reachable
 * on a local network but NOT across NATs — cross-country reachability needs [StunEndpointResolver]
 * (flagged). Falls back to loopback if the LAN address can't be determined.
 */
class LocalEndpointResolver : EndpointResolver {
    override fun resolve(port: Int): Endpoint {
        val host = runCatching { InetAddress.getLocalHost().hostAddress }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "127.0.0.1"
        return Endpoint(host, port)
    }
}
