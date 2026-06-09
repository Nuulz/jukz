package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MessageCodecTest {

    private val world = WorldId.random()
    private val token = ClaimToken(7, 123456789, NodeId.random())
    private val other = ClaimToken(9, 987654321, NodeId.random())

    private val all: List<Message> = listOf(
        Message.Hello(world, token, 1, HandshakeRole.JOINER),
        Message.Hello(world, token, 1, HandshakeRole.HOST, protoVersion = 3),
        Message.Claim(world, token, 2),
        Message.Ping(world, token, 3),
        Message.Pong(world, token, 4, heartbeatSeq = 42),
        Message.Redirect(world, token, 5, Endpoint("198.51.100.7", 25565)),
        Message.Yield(world, token, 6, winnerToken = other),
        Message.Ack(world, token, 7),
        Message.Nack(world, token, 8, NackReason.STALE_TOKEN),
    )

    @Test
    fun `every variant round-trips byte-exactly`() {
        for (m in all) {
            val encoded = MessageCodec.encode(m)
            val decoded = MessageCodec.decode(encoded)
            assertEquals(m, decoded, "decode mismatch for $m")
            assertArrayEquals(encoded, MessageCodec.encode(decoded), "re-encode mismatch for $m")
        }
    }

    @Test
    fun `truncated buffer is rejected`() {
        val encoded = MessageCodec.encode(all.first())
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode(encoded.copyOfRange(0, encoded.size - 2))
        }
    }

    @Test
    fun `unknown type byte is rejected`() {
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode(byteArrayOf(99, 0, 0, 0))
        }
    }

    @Test
    fun `trailing bytes are rejected`() {
        val encoded = MessageCodec.encode(all.first())
        assertThrows(MalformedMessageException::class.java) {
            MessageCodec.decode(encoded + byteArrayOf(0, 0))
        }
    }
}
