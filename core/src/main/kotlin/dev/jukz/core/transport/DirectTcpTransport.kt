package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plain outbound TCP transport for the case where the host endpoint is directly reachable
 * (LAN, UPnP-mapped, or port-forwarded). The NAT-traversing transport (ICE/hole-punch/relay)
 * implements the same interface and is selected when a direct connect is not possible.
 */
class DirectTcpTransport(private val connectTimeoutMs: Int = 4_000) : Transport {
    override suspend fun connect(endpoint: Endpoint): JukzChannel {
        val socket = Socket()
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
        return SocketChannel(socket)
    }
}
