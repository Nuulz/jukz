package dev.jukz.core.join

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.handshake.FramedMessageChannel
import dev.jukz.core.handshake.HostStateMachine
import dev.jukz.core.handshake.Message
import dev.jukz.core.handshake.NackReason
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.DirectChannelDialer
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.SocketChannel
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class JoinControllerLoopbackTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val hostToken = ClaimToken(5, 100, node(5))

    // Short liveness windows keep the controller from lingering; handshake is local so it's instant.
    private val config = JoinConfig(handshakeMs = 2_000, livenessIntervalMs = 60_000, livenessTimeoutMs = 60_000)

    @Test
    fun `connects end-to-end to a live host and pipes bytes through the relay`() = runBlocking {
        val host = LoopbackTestHost(world, hostToken, LoopbackTestHost.Mode.ALIVE).also { it.start() }
        val registry = InMemoryWorldRegistry(SystemClock)
        registry.publishIfNewer(WorldRecord(world, hostToken, host.endpoint, 0))
        val handoff = FakeGameHandoff()
        val controller = JoinController(registry, DirectChannelDialer(DirectTcpTransport()),handoff, SystemClock, config)

        val result = controller.join(world)

        val connected = assertInstanceOf(JoinResult.Connected::class.java, result)
        assertEquals("127.0.0.1", connected.host)
        assertEquals(connected.host to connected.port, handoff.connectedTo)

        // The relay is a transparent pipe: client -> relay -> data channel -> host echo -> back.
        val client = Socket("127.0.0.1", connected.port)
        client.getOutputStream().apply { write("ping".toByteArray()); flush() }
        val buf = ByteArray(4)
        var off = 0
        while (off < buf.size) {
            val n = client.getInputStream().read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        assertEquals("ping", String(buf))

        client.close()
        controller.close()
        host.close()
    }

    @Test
    fun `tries the next announced endpoint when the first is unreachable`() = runBlocking {
        val host = LoopbackTestHost(world, hostToken, LoopbackTestHost.Mode.ALIVE).also { it.start() }
        // A dead endpoint: bind to grab a free port, then close it so connects are refused.
        val dead = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val deadEndpoint = Endpoint("127.0.0.1", dead.localPort)
        dead.close()
        val registry = InMemoryWorldRegistry(SystemClock)
        registry.publishIfNewer(WorldRecord(world, hostToken, listOf(deadEndpoint, host.endpoint), 0))
        val handoff = FakeGameHandoff()
        val controller = JoinController(registry, DirectChannelDialer(DirectTcpTransport()),handoff, SystemClock, config)

        val result = controller.join(world)

        val connected = assertInstanceOf(JoinResult.Connected::class.java, result)
        assertEquals(connected.host to connected.port, handoff.connectedTo)

        controller.close()
        host.close()
    }

    @Test
    fun `reports ShouldHost when the announced host is a ghost`() = runBlocking {
        val host = LoopbackTestHost(world, hostToken, LoopbackTestHost.Mode.GHOST_NACK).also { it.start() }
        val registry = InMemoryWorldRegistry(SystemClock)
        registry.publishIfNewer(WorldRecord(world, hostToken, host.endpoint, 0))
        val controller = JoinController(registry, DirectChannelDialer(DirectTcpTransport()),FakeGameHandoff(), SystemClock, config)

        val result = controller.join(world)

        val shouldHost = assertInstanceOf(JoinResult.ShouldHost::class.java, result)
        assertEquals(world, shouldHost.worldId)

        controller.close()
        host.close()
    }

    @Test
    fun `reports HostUnavailable when no live record exists`() = runBlocking {
        val registry = InMemoryWorldRegistry(SystemClock)
        val controller = JoinController(registry, DirectChannelDialer(DirectTcpTransport()),FakeGameHandoff(), SystemClock, config)

        assertEquals(JoinResult.HostUnavailable, controller.join(world))

        controller.close()
    }
}

/** Captures the local endpoint the controller hands the vanilla client off to. */
private class FakeGameHandoff : GameHandoff {
    @Volatile
    var connectedTo: Pair<String, Int>? = null

    override fun connect(host: String, port: Int) {
        connectedTo = host to port
    }
}

/**
 * A loopback stand-in for a real jukz host: reads the connection-type byte, drives a
 * [HostStateMachine] on control channels and echoes bytes on data channels. [Mode.GHOST_NACK]
 * simulates a stale/dead host that always NACKs, so the joiner falls into takeover.
 */
private class LoopbackTestHost(
    private val world: WorldId,
    private val hostToken: ClaimToken,
    private val mode: Mode,
) : AutoCloseable {
    enum class Mode { ALIVE, GHOST_NACK }

    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val endpoint: Endpoint get() = Endpoint("127.0.0.1", server.localPort)

    fun start() {
        Thread {
            while (true) {
                val sock = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { handle(sock) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(sock: Socket) {
        runCatching {
            val ch = SocketChannel(sock)
            when (ConnectionType.readFrom(ch)) {
                ConnectionType.CONTROL -> handleControl(ch)
                ConnectionType.DATA -> echo(sock)
                ConnectionType.SNAPSHOT -> Unit // this double never offers a snapshot
            }
        }
    }

    private fun handleControl(ch: JukzChannel) {
        val framed = FramedMessageChannel(ch)
        val sm = HostStateMachine(world, hostToken) { 1L }
        while (true) {
            val msg = try {
                framed.receive()
            } catch (_: Exception) {
                return
            }
            when (mode) {
                Mode.ALIVE -> sm.onMessage(msg).reply?.let { framed.send(it) }
                Mode.GHOST_NACK ->
                    framed.send(Message.Nack(world, hostToken, msg.nonce, NackReason.STALE_TOKEN))
            }
        }
    }

    private fun echo(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n < 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
