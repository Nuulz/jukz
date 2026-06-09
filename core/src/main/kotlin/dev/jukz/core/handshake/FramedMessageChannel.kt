package dev.jukz.core.handshake

import dev.jukz.core.transport.JukzChannel
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream

/**
 * Length-prefixed framing for [Message] over a [JukzChannel]. [MessageCodec] serialises a single
 * message but does not delimit it on a byte stream, so the control channel wraps each encoded
 * message in a 4-byte big-endian length prefix. [send] is safe to call from multiple threads;
 * [receive] is single-reader.
 */
class FramedMessageChannel(private val channel: JukzChannel) {

    private val input = DataInputStream(channel.inputStream())
    private val output: OutputStream = channel.outputStream()

    /** Encode [message] and write it as `int32 length + bytes`, flushing immediately. */
    fun send(message: Message) {
        val payload = MessageCodec.encode(message)
        synchronized(output) {
            output.write(intToBytes(payload.size))
            output.write(payload)
            output.flush()
        }
    }

    /** Read one framed message, blocking until a full frame is available. */
    fun receive(): Message {
        val len = try {
            input.readInt()
        } catch (e: EOFException) {
            throw MalformedMessageException("stream closed before a frame length", e)
        }
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw MalformedMessageException("frame length out of range: $len")
        }
        val buf = ByteArray(len)
        try {
            input.readFully(buf)
        } catch (e: EOFException) {
            throw MalformedMessageException("stream closed mid-frame (wanted $len bytes)", e)
        }
        return MessageCodec.decode(buf)
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        /** Handshake messages are tiny; this cap guards against a hostile/garbled length prefix. */
        const val MAX_FRAME_BYTES = 64 * 1024
    }
}
