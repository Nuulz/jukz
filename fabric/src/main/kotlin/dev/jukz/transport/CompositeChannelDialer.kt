package dev.jukz.transport

import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.Transport

/**
 * Production [ChannelDialer]: a direct TCP [Transport] for [DialTarget.Direct], and the relay
 * WebSocket for [DialTarget.ViaRelay]. The guest's ladder tries direct targets first, so the relay
 * client is only invoked when every direct endpoint has failed.
 */
class CompositeChannelDialer(
    private val relay: WsRelayTransport,
    private val direct: Transport = DirectTcpTransport(),
) : ChannelDialer {
    override suspend fun dial(target: DialTarget): JukzChannel = when (target) {
        is DialTarget.Direct -> direct.connect(target.endpoint)
        is DialTarget.ViaRelay -> relay.connect(target.sessionId)
    }
}
