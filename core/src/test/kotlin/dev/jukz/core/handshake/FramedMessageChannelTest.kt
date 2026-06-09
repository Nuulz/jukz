package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.JukzChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class FramedMessageChannelTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val token = ClaimToken(5, 100, node(5))

    @Test
    fun `round-trips a sequence of framed messages over a stream`() {
        val sink = ByteArrayOutputStream()
        val writer = FramedMessageChannel(outChannel(sink))
        val sent = listOf(
            Message.Hello(world, token, 1, HandshakeRole.JOINER),
            Message.Claim(world, token, 2),
            Message.Redirect(world, token, 3, Endpoint("host", 30000)),
            Message.Pong(world, token, 4, 99),
            Message.Nack(world, token, 5, NackReason.STALE_TOKEN),
        )
        sent.forEach { writer.send(it) }

        val reader = FramedMessageChannel(inChannel(sink.toByteArray()))
        val received = sent.indices.map { reader.receive() }

        assertEquals(sent, received)
    }

    @Test
    fun `rejects a frame whose length exceeds the cap`() {
        // A 4-byte length prefix claiming a huge frame, with no body.
        val bogus = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val reader = FramedMessageChannel(inChannel(bogus))
        assertThrows(MalformedMessageException::class.java) { reader.receive() }
    }

    private fun outChannel(out: OutputStream): JukzChannel = object : JukzChannel {
        override fun inputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun outputStream(): OutputStream = out
        override fun close() {}
    }

    private fun inChannel(bytes: ByteArray): JukzChannel = object : JukzChannel {
        override fun inputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun outputStream(): OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
