package dev.jukz.core.transport

import java.io.EOFException

/**
 * One-byte discriminator written as the very first byte of every jukz connection, so the host can
 * route an inbound channel without protocol sniffing: a [CONTROL] channel carries the jukz
 * handshake plus liveness, a [DATA] channel is a transparent byte pipe to the LAN-opened Minecraft
 * server. The guest writes the byte right after the transport connects; the host reads it first.
 */
enum class ConnectionType(val wireByte: Int) {
    CONTROL(0x01),
    DATA(0x02);

    /** Write this discriminator to [channel] and flush. */
    fun writeTo(channel: JukzChannel) {
        val out = channel.outputStream()
        out.write(wireByte)
        out.flush()
    }

    companion object {
        /** Read and resolve the leading discriminator byte from [channel]. */
        fun readFrom(channel: JukzChannel): ConnectionType {
            val b = channel.inputStream().read()
            if (b < 0) throw EOFException("connection closed before a type byte")
            return fromByte(b)
        }

        fun fromByte(b: Int): ConnectionType =
            entries.firstOrNull { it.wireByte == b }
                ?: throw IllegalArgumentException("unknown connection type byte: $b")
    }
}
