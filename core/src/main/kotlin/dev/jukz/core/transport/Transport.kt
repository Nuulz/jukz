package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional byte stream to a peer. Implementations: a plain TCP socket (direct/relay)
 * or a QUIC stream over a hole-punched UDP path. The bytes are opaque (Minecraft's own
 * end-to-end encryption rides on top), so the channel is a dumb pipe.
 */
interface JukzChannel : Closeable {
    fun inputStream(): InputStream
    fun outputStream(): OutputStream
}

/** Establishes a [JukzChannel] to a host endpoint, abstracting how reachability was obtained. */
interface Transport {
    suspend fun connect(endpoint: Endpoint): JukzChannel
}
