package dev.jukz.core.host

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.join.GameHandoff
import dev.jukz.core.join.JoinConfig
import dev.jukz.core.join.JoinController
import dev.jukz.core.join.JoinResult
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * End-to-end over loopback with the REAL host: a [HostController] (real [HostConnectionServer])
 * publishes and serves, and a real [JoinController] discovers it, completes the jukz handshake, and
 * pipes bytes through to the host's game — no test-double host. This is the proof the whole protocol
 * (discovery → CONTROL handshake/fencing → DATA relay) works against a genuine host, which is exactly
 * what the live DHT/NAT adapters slot under without touching the protocol.
 */
class HostJoinLoopbackTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val config = JoinConfig(handshakeMs = 2_000, livenessIntervalMs = 60_000, livenessTimeoutMs = 60_000)

    @Test
    fun `real host serves a real guest end-to-end and pipes game bytes`() = runBlocking {
        // Stand-in for the openToLan Minecraft server: a loopback echo server.
        val game = EchoServer().also { it.start() }
        val registry = InMemoryWorldRegistry(SystemClock)

        val host = HostController(
            registry = registry,
            lanOpener = LanOpener { game.port },
            connectionServer = HostConnectionServer(bindHost = "127.0.0.1"),
            endpointResolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) },
            nodeId = node(7),
            clock = SystemClock,
        )

        val hosting = assertInstanceOf(HostResult.Hosting::class.java, host.host(world, generation = 4))
        // The announced endpoint is the connection server, not the game port.
        assertEquals(listOf(Endpoint("127.0.0.1", hosting.port)), registry.lookup(world)!!.endpoints)

        val handoff = CapturingHandoff()
        val joiner = JoinController(registry, DirectTcpTransport(), handoff, SystemClock, config)

        val result = joiner.join(world)

        val connected = assertInstanceOf(JoinResult.Connected::class.java, result)
        assertEquals(connected.host to connected.port, handoff.connectedTo)

        // The full chain is transparent: client -> relay -> DATA channel -> host server -> game echo.
        val client = Socket("127.0.0.1", connected.port)
        client.getOutputStream().apply { write("jukz".toByteArray()); flush() }
        val buf = ByteArray(4)
        var off = 0
        while (off < buf.size) {
            val n = client.getInputStream().read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        assertEquals("jukz", String(buf))

        client.close()
        joiner.close()
        host.close()
        game.close()
    }
}

/** Captures the loopback relay endpoint the guest is handed off to. */
private class CapturingHandoff : GameHandoff {
    @Volatile var connectedTo: Pair<String, Int>? = null
    override fun connect(host: String, port: Int) {
        connectedTo = host to port
    }
}

/** A loopback echo server standing in for the host's openToLan Minecraft server. */
private class EchoServer : AutoCloseable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = server.localPort

    fun start() {
        Thread {
            while (true) {
                val sock = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                Thread { echo(sock) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
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
