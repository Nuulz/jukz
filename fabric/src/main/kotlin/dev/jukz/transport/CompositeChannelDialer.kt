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
 *
 * [forceRelay] (dev/testing) makes every direct target fail instantly, so the ladder falls straight
 * to the relay — letting the relay path be exercised on one machine where the direct path would
 * otherwise work. See `JukzConfig.forceRelay`.
 */
class CompositeChannelDialer(
    private val relay: WsRelayTransport,
    private val direct: Transport = DirectTcpTransport(),
    private val forceRelay: Boolean = false,
) : ChannelDialer {
    override suspend fun dial(target: DialTarget): JukzChannel = when (target) {
        is DialTarget.Direct ->
            if (forceRelay) throw java.io.IOException("force-relay: skipping direct endpoint ${target.endpoint}")
            else direct.connect(target.endpoint)
        is DialTarget.ViaRelay -> relay.connect(target.sessionId)
    }
}
