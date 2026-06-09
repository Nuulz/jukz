package dev.jukz.core.transport

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class LocalTcpRelayTest {

    @Test
    fun `relay pipes bytes in both directions including a large payload`() {
        // A loopback echo "host" the relay forwards to.
        val echo = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        Thread {
            runCatching {
                val s = echo.accept()
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            }
        }.apply { isDaemon = true }.start()

        val relay = LocalTcpRelay(openRemote = { SocketChannel(Socket("127.0.0.1", echo.localPort)) })
        val port = relay.start()

        val client = Socket("127.0.0.1", port)
        val payload = ByteArray(64 * 1024) { (it * 31).toByte() } // larger than the 8K pump buffer
        client.getOutputStream().write(payload)
        client.getOutputStream().flush()

        val received = ByteArray(payload.size)
        var off = 0
        val input = client.getInputStream()
        while (off < payload.size) {
            val n = input.read(received, off, payload.size - off)
            if (n < 0) break
            off += n
        }

        assertEquals(payload.size, off)
        assertArrayEquals(payload, received)

        client.close()
        relay.close()
        echo.close()
    }
}
