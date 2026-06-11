package dev.jukz.core.join

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.host.HostConnectionServer
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class JoinControllerRelayLadderTest {

    private val world = WorldId.random()
    private val token = ClaimToken(1, 1, NodeId(ByteArray(16) { 5 }))

    @Test
    fun `falls back to the relay target when every direct endpoint fails`() = runBlocking {
        // A real host listening on loopback; the "relay" just dials it directly in this test.
        val server = HostConnectionServer(bindHost = "127.0.0.1")
        val port = server.start(world, token, Endpoint("127.0.0.1", 1)) { 0 }
        try {
            val registry = InMemoryWorldRegistry(SystemClock)
            registry.publishIfNewer(
                WorldRecord(
                    worldId = world,
                    token = token,
                    endpoints = listOf(Endpoint("203.0.113.255", 1)), // unreachable on purpose
                    heartbeatSeq = 0,
                    relay = RelayOffer("cap-loopback"),
                ),
            )

            val direct = DirectTcpTransport(connectTimeoutMs = 200)
            val dialer = ChannelDialer { target ->
                when (target) {
                    is DialTarget.Direct -> direct.connect(target.endpoint) // throws (unreachable)
                    is DialTarget.ViaRelay -> direct.connect(Endpoint("127.0.0.1", port)) // "relay" -> loopback host
                }
            }

            val connected = AtomicBoolean(false)
            val handoff = object : GameHandoff {
                override fun connect(host: String, port: Int) { connected.set(true) }
            }
            JoinController(registry, dialer, handoff, SystemClock).use { controller ->
                assertInstanceOf(JoinResult.Connected::class.java, controller.join(world))
            }
            assertTrue(connected.get())
        } finally {
            server.close()
        }
    }
}
