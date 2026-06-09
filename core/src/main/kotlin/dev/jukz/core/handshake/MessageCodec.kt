package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.ByteReader
import dev.jukz.core.util.ByteWriter
import dev.jukz.core.util.MalformedDataException
import java.nio.ByteBuffer
import java.util.UUID

class MalformedMessageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Binary wire codec for [Message]. Layout: type byte, then common header
 * (worldId 16B, token, nonce 4B), then per-type fields. Round-trips byte-exactly.
 */
object MessageCodec {

    private const val T_HELLO = 1
    private const val T_CLAIM = 2
    private const val T_PING = 3
    private const val T_PONG = 4
    private const val T_REDIRECT = 5
    private const val T_YIELD = 6
    private const val T_ACK = 7
    private const val T_NACK = 8

    fun encode(message: Message): ByteArray {
        val w = ByteWriter()
        w.putByte(typeByte(message))
        w.putRaw(uuidToBytes(message.worldId.uuid))
        encodeToken(w, message.token)
        w.putInt(message.nonce)
        when (message) {
            is Message.Hello -> {
                w.putByte(message.role.ordinal)
                w.putInt(message.protoVersion)
            }
            is Message.Pong -> w.putLong(message.heartbeatSeq)
            is Message.Redirect -> w.putString(message.endpoint.format())
            is Message.Yield -> encodeToken(w, message.winnerToken)
            is Message.Nack -> w.putByte(message.reason.ordinal)
            is Message.Claim, is Message.Ping, is Message.Ack -> {} // header only
        }
        return w.toByteArray()
    }

    fun decode(bytes: ByteArray): Message {
        try {
            val r = ByteReader(bytes)
            val type = r.getByte()
            val worldId = WorldId(bytesToUuid(r.getRaw(16)))
            val token = decodeToken(r)
            val nonce = r.getInt()
            val message = when (type) {
                T_HELLO -> Message.Hello(worldId, token, nonce, decodeRole(r.getByte()), r.getInt())
                T_CLAIM -> Message.Claim(worldId, token, nonce)
                T_PING -> Message.Ping(worldId, token, nonce)
                T_PONG -> Message.Pong(worldId, token, nonce, r.getLong())
                T_REDIRECT -> Message.Redirect(worldId, token, nonce, Endpoint.parse(r.getString()))
                T_YIELD -> Message.Yield(worldId, token, nonce, decodeToken(r))
                T_ACK -> Message.Ack(worldId, token, nonce)
                T_NACK -> Message.Nack(worldId, token, nonce, decodeReason(r.getByte()))
                else -> throw MalformedMessageException("unknown message type byte: $type")
            }
            r.expectEnd()
            return message
        } catch (e: MalformedDataException) {
            throw MalformedMessageException("malformed message: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw MalformedMessageException("invalid message field: ${e.message}", e)
        }
    }

    private fun typeByte(m: Message): Int = when (m) {
        is Message.Hello -> T_HELLO
        is Message.Claim -> T_CLAIM
        is Message.Ping -> T_PING
        is Message.Pong -> T_PONG
        is Message.Redirect -> T_REDIRECT
        is Message.Yield -> T_YIELD
        is Message.Ack -> T_ACK
        is Message.Nack -> T_NACK
    }

    private fun encodeToken(w: ByteWriter, t: ClaimToken) {
        w.putLong(t.hostGeneration)
        w.putLong(t.claimEpochMillis)
        w.putRaw(t.nodeId.bytes)
    }

    private fun decodeToken(r: ByteReader): ClaimToken {
        val gen = r.getLong()
        val millis = r.getLong()
        val nodeId = NodeId(r.getRaw(NodeId.SIZE))
        return ClaimToken(gen, millis, nodeId)
    }

    private fun decodeRole(ordinal: Int): HandshakeRole {
        val values = HandshakeRole.entries
        if (ordinal !in values.indices) throw MalformedMessageException("bad role ordinal: $ordinal")
        return values[ordinal]
    }

    private fun decodeReason(ordinal: Int): NackReason {
        val values = NackReason.entries
        if (ordinal !in values.indices) throw MalformedMessageException("bad nack reason ordinal: $ordinal")
        return values[ordinal]
    }

    private fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun bytesToUuid(b: ByteArray): UUID {
        val bb = ByteBuffer.wrap(b)
        return UUID(bb.long, bb.long)
    }
}
