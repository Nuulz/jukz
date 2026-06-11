package dev.jukz.core.host

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ConnectionType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The host serves an armed world snapshot over a [ConnectionType.SNAPSHOT] channel on the SAME
 * connection-server port the game uses — so the take-over (F4 handoff) rides the one NAT traversal
 * that already works for play, with no second port to forward.
 */
class SnapshotChannelTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val token = ClaimToken(4, 1_700_000_000_000, node(7))

    @Test
    fun `serves the armed pack and its head to a guest presenting the right token`() {
        val server = HostConnectionServer(bindHost = "127.0.0.1")
        val port = server.start(world, token, Endpoint("127.0.0.1", 1)) { 0 }
        try {
            val pack = ByteArray(2048) { (it % 251).toByte() }
            val head = "0123456789abcdef0123456789abcdef01234567"
            val latch = server.armSnapshot(pack, head, "secret-token")

            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().also { it.write(ConnectionType.SNAPSHOT.wireByte); it.flush() }
                val out = DataOutputStream(sock.getOutputStream())
                out.writeUTF("secret-token"); out.flush()

                val din = DataInputStream(sock.getInputStream())
                assertEquals(1, din.readByte().toInt()) // accepted
                assertEquals(head, din.readUTF())
                val size = din.readLong()
                val received = ByteArray(size.toInt())
                din.readFully(received)
                assertArrayEquals(pack, received)
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS)) // a completed download counts the latch down
        } finally {
            server.close()
        }
    }

    @Test
    fun `rejects a guest presenting the wrong token without serving the pack`() {
        val server = HostConnectionServer(bindHost = "127.0.0.1")
        val port = server.start(world, token, Endpoint("127.0.0.1", 1)) { 0 }
        try {
            server.armSnapshot(byteArrayOf(9, 9, 9), "f".repeat(40), "right-token")

            Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().also { it.write(ConnectionType.SNAPSHOT.wireByte); it.flush() }
                val out = DataOutputStream(sock.getOutputStream())
                out.writeUTF("wrong-token"); out.flush()

                val din = DataInputStream(sock.getInputStream())
                assertEquals(0, din.readByte().toInt()) // rejected; no head/pack follows
            }
        } finally {
            server.close()
        }
    }
}
