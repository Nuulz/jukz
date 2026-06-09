package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId

enum class HostState { LISTENING, YIELDED }

/** What the host should do in response to one incoming message. */
data class HostReaction(val state: HostState, val reply: Message?, val shutdown: Boolean = false)

/**
 * Host side of the handshake. The host socket is the fenced resource (spec R6/R14): it
 * yields to a strictly higher token and rejects strictly-lower-generation (stale) tokens,
 * so mutual exclusion is enforced resource-side, not just by clients.
 */
class HostStateMachine(
    private val worldId: WorldId,
    private val myToken: ClaimToken,
    private val heartbeatSeq: () -> Long,
) {
    var state: HostState = HostState.LISTENING
        private set

    fun onMessage(msg: Message): HostReaction {
        if (state == HostState.YIELDED) {
            return settle(HostState.YIELDED, nack(msg, NackReason.NOT_HOST))
        }
        if (msg.worldId != worldId) {
            return settle(HostState.LISTENING, nack(msg, NackReason.WRONG_WORLD))
        }
        // R6: any strictly higher token wins -> yield and shut the socket.
        if (msg.token > myToken) {
            state = HostState.YIELDED
            return HostReaction(HostState.YIELDED, Message.Yield(worldId, myToken, msg.nonce, msg.token), shutdown = true)
        }
        // R14: strictly lower generation than ours is stale.
        if (msg.token.hostGeneration < myToken.hostGeneration) {
            return settle(HostState.LISTENING, nack(msg, NackReason.STALE_TOKEN))
        }
        // We remain authoritative; answer normally (competitor will see our higher token and yield).
        return when (msg) {
            is Message.Hello -> settle(HostState.LISTENING, Message.Claim(worldId, myToken, msg.nonce))
            is Message.Claim -> settle(HostState.LISTENING, Message.Claim(worldId, myToken, msg.nonce))
            is Message.Ping -> settle(HostState.LISTENING, Message.Pong(worldId, myToken, msg.nonce, heartbeatSeq()))
            else -> settle(HostState.LISTENING, Message.Ack(worldId, myToken, msg.nonce))
        }
    }

    private fun settle(s: HostState, reply: Message?): HostReaction {
        state = s
        return HostReaction(s, reply)
    }

    private fun nack(msg: Message, reason: NackReason) =
        Message.Nack(worldId, myToken, msg.nonce, reason)
}
