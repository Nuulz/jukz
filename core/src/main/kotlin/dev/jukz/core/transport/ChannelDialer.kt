package dev.jukz.core.transport

/**
 * Opens a raw [JukzChannel] to a [DialTarget], abstracting over the direct-TCP and relay-WebSocket
 * paths. The production implementation (fabric) composes a [Transport] for [DialTarget.Direct] with
 * a relay WebSocket client for [DialTarget.ViaRelay]; [DirectChannelDialer] is the direct-only one
 * used on the LAN path and in tests.
 */
fun interface ChannelDialer {
    suspend fun dial(target: DialTarget): JukzChannel
}

/** A [ChannelDialer] that only knows the direct path; a relay target is an error. */
class DirectChannelDialer(private val transport: Transport = DirectTcpTransport()) : ChannelDialer {
    override suspend fun dial(target: DialTarget): JukzChannel = when (target) {
        is DialTarget.Direct -> transport.connect(target.endpoint)
        is DialTarget.ViaRelay ->
            throw UnsupportedOperationException("DirectChannelDialer cannot dial a relay target")
    }
}
