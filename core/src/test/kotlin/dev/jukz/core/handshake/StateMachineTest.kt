package dev.jukz.core.handshake

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StateMachineTest {

    private val world = WorldId.random()
    private val otherWorld = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val myToken = ClaimToken(5, 100, node(5))

    // ---- Host ----

    @Test
    fun `host replies CLAIM to a HELLO`() {
        val host = HostStateMachine(world, myToken) { 7 }
        val r = host.onMessage(Message.Hello(world, ClaimToken(5, 100, node(3)), 1, HandshakeRole.JOINER))
        assertInstanceOf(Message.Claim::class.java, r.reply)
        assertEquals(HostState.LISTENING, r.state)
    }

    @Test
    fun `host replies PONG with current heartbeatSeq to a PING`() {
        val host = HostStateMachine(world, myToken) { 7 }
        val r = host.onMessage(Message.Ping(world, ClaimToken(5, 100, node(3)), 2))
        val pong = assertInstanceOf(Message.Pong::class.java, r.reply)
        assertEquals(7, pong.heartbeatSeq)
    }

    @Test
    fun `host yields and shuts down on a strictly higher token (R6)`() {
        val host = HostStateMachine(world, myToken) { 7 }
        val higher = ClaimToken(6, 0, node(0))
        val r = host.onMessage(Message.Claim(world, higher, 3))
        val yield_ = assertInstanceOf(Message.Yield::class.java, r.reply)
        assertEquals(higher, yield_.winnerToken)
        assertTrue(r.shutdown)
        assertEquals(HostState.YIELDED, r.state)
    }

    @Test
    fun `host NACKs a stale lower-generation token (R14)`() {
        val host = HostStateMachine(world, myToken) { 7 }
        val r = host.onMessage(Message.Hello(world, ClaimToken(4, 0, node(0)), 4, HandshakeRole.JOINER))
        val nack = assertInstanceOf(Message.Nack::class.java, r.reply)
        assertEquals(NackReason.STALE_TOKEN, nack.reason)
    }

    @Test
    fun `host NACKs a message for the wrong world`() {
        val host = HostStateMachine(world, myToken) { 7 }
        val r = host.onMessage(Message.Hello(otherWorld, myToken, 5, HandshakeRole.JOINER))
        val nack = assertInstanceOf(Message.Nack::class.java, r.reply)
        assertEquals(NackReason.WRONG_WORLD, nack.reason)
    }

    // ---- Joiner ----

    private val hostEp = Endpoint("host", 30000)

    @Test
    fun `joiner proceeds to JOINING on a CLAIM at or above expected`() {
        val joiner = JoinerStateMachine(world, myToken, hostEp)
        joiner.begin()
        val r = joiner.onMessage(Message.Claim(world, myToken, 1))
        assertEquals(JoinerState.JOINING, r.state)
        val connect = assertInstanceOf(JoinerAction.Connect::class.java, r.action)
        assertEquals(hostEp, connect.endpoint)
    }

    @Test
    fun `joiner follows a REDIRECT to a new endpoint`() {
        val joiner = JoinerStateMachine(world, myToken, hostEp)
        joiner.begin()
        val newEp = Endpoint("new", 40000)
        val r = joiner.onMessage(Message.Redirect(world, ClaimToken(6, 0, node(0)), 2, newEp))
        assertEquals(JoinerState.CONNECTING, r.state)
        assertEquals(newEp, joiner.currentEndpoint)
        assertInstanceOf(JoinerAction.FollowRedirect::class.java, r.action)
    }

    @Test
    fun `joiner takes over on a connect timeout (ghost)`() {
        val joiner = JoinerStateMachine(world, myToken, hostEp)
        joiner.begin()
        val r = joiner.onTimeout()
        assertEquals(JoinerState.GHOST, r.state)
        assertEquals(JoinerAction.Takeover, r.action)
    }

    @Test
    fun `joiner takes over on a STALE_TOKEN nack`() {
        val joiner = JoinerStateMachine(world, myToken, hostEp)
        joiner.begin()
        val r = joiner.onMessage(Message.Nack(world, myToken, 3, NackReason.STALE_TOKEN))
        assertEquals(JoinerState.GHOST, r.state)
        assertEquals(JoinerAction.Takeover, r.action)
    }
}
