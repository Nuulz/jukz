package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId

enum class JoinerState { DISCOVER, CONNECTING, JOINING, CONNECTED, GHOST }

/** The action the joiner runtime should take after a transition. */
sealed interface JoinerAction {
    /** Hand off to the vanilla Minecraft join, targeting [endpoint] (via the local relay). */
    data class Connect(val endpoint: Endpoint) : JoinerAction
    /** Reconnect the handshake to a new endpoint. */
    data class FollowRedirect(val endpoint: Endpoint) : JoinerAction
    /** The announced host is a ghost; become host instead. */
    data object Takeover : JoinerAction
    /** Nothing to do yet. */
    data object Wait : JoinerAction
}

data class JoinerReaction(val state: JoinerState, val action: JoinerAction)

/**
 * Joiner side of the handshake: DISCOVER -> CONNECTING -> JOINING -> CONNECTED, with
 * REDIRECT following a new endpoint and connect/liveness timeout or stale host -> GHOST -> TAKEOVER.
 */
class JoinerStateMachine(
    private val worldId: WorldId,
    expectedHostToken: ClaimToken,
    candidateEndpoint: Endpoint,
) {
    var state: JoinerState = JoinerState.DISCOVER
        private set

    var currentEndpoint: Endpoint = candidateEndpoint
        private set

    private var expected: ClaimToken = expectedHostToken

    /** Move from DISCOVER to CONNECTING (start the handshake). */
    fun begin(): JoinerReaction {
        state = JoinerState.CONNECTING
        return JoinerReaction(state, JoinerAction.Wait)
    }

    fun onMessage(msg: Message): JoinerReaction {
        if (msg.worldId != worldId) return JoinerReaction(state, JoinerAction.Wait)
        return when (msg) {
            is Message.Claim -> {
                if (msg.token >= expected) {
                    expected = msg.token
                    state = JoinerState.JOINING
                    JoinerReaction(state, JoinerAction.Connect(currentEndpoint))
                } else {
                    // Host advertised a stale token: it is effectively a ghost.
                    state = JoinerState.GHOST
                    JoinerReaction(state, JoinerAction.Takeover)
                }
            }
            is Message.Redirect -> {
                currentEndpoint = msg.endpoint
                expected = msg.token
                state = JoinerState.CONNECTING
                JoinerReaction(state, JoinerAction.FollowRedirect(msg.endpoint))
            }
            is Message.Nack -> when (msg.reason) {
                NackReason.NOT_HOST, NackReason.STALE_TOKEN -> {
                    state = JoinerState.GHOST
                    JoinerReaction(state, JoinerAction.Takeover)
                }
                NackReason.WRONG_WORLD, NackReason.BAD_PROTO ->
                    JoinerReaction(state, JoinerAction.Wait)
            }
            else -> JoinerReaction(state, JoinerAction.Wait)
        }
    }

    /** Connect or liveness timeout -> the host is a ghost -> take over. */
    fun onTimeout(): JoinerReaction {
        state = JoinerState.GHOST
        return JoinerReaction(state, JoinerAction.Takeover)
    }

    /** Vanilla join succeeded. */
    fun markConnected(): JoinerReaction {
        state = JoinerState.CONNECTED
        return JoinerReaction(state, JoinerAction.Wait)
    }
}
