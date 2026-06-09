package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId

enum class HandshakeRole { JOINER, HOST }

enum class NackReason { STALE_TOKEN, WRONG_WORLD, BAD_PROTO, NOT_HOST }

/**
 * The transport-agnostic jukz handshake message set (spec §6). Runs over the jukz transport
 * AFTER a DHT lookup and BEFORE the vanilla Minecraft join. Every message carries the world id,
 * the sender's claim token, and a nonce for ACK matching.
 */
sealed interface Message {
    val worldId: WorldId
    val token: ClaimToken
    val nonce: Int

    data class Hello(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
        val role: HandshakeRole,
        val protoVersion: Int = PROTOCOL_VERSION,
    ) : Message

    data class Claim(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
    ) : Message

    data class Ping(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
    ) : Message

    data class Pong(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
        val heartbeatSeq: Long,
    ) : Message

    data class Redirect(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
        val endpoint: Endpoint,
    ) : Message

    data class Yield(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
        val winnerToken: ClaimToken,
    ) : Message

    data class Ack(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
    ) : Message

    data class Nack(
        override val worldId: WorldId,
        override val token: ClaimToken,
        override val nonce: Int,
        val reason: NackReason,
    ) : Message

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
