package dev.jukz.core.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** [JukzChannel] backed by a plain TCP [Socket] (direct or relayed connection). */
class SocketChannel(private val socket: Socket) : JukzChannel {
    override fun inputStream(): InputStream = socket.getInputStream()
    override fun outputStream(): OutputStream = socket.getOutputStream()
    override fun close() {
        runCatching { socket.close() }
    }
}
