package dev.jukz.core.transport

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The guest-side bridge (spec §4.3). Listens on `127.0.0.1:<ephemeral>`; the vanilla
 * Minecraft client connects there as if to a normal server, and the relay pipes the raw
 * byte stream to a [JukzChannel] reaching the real host. Minecraft's encryption is
 * end-to-end and excludes the address from the auth hash, so a transparent byte pump
 * preserves online-mode auth — no protocol awareness required.
 *
 * One fresh remote [JukzChannel] is opened per inbound local connection via [openRemote].
 */
class LocalTcpRelay(
    private val openRemote: () -> JukzChannel,
    private val bindHost: String = "127.0.0.1",
    private val bufferSize: Int = 8 * 1024,
) : Closeable {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val pumps = CopyOnWriteArrayList<Thread>()

    /** Binds and starts accepting; returns the chosen local port. */
    fun start(): Int {
        val s = ServerSocket()
        s.bind(InetSocketAddress(bindHost, 0))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "jukz-relay-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    val port: Int get() = server?.localPort ?: error("relay not started")

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val local = try {
                s.accept()
            } catch (_: Exception) {
                if (running) continue else break
            }
            handle(local)
        }
    }

    private fun handle(local: Socket) {
        val remote: JukzChannel = try {
            openRemote()
        } catch (_: Exception) {
            runCatching { local.close() }
            return
        }
        // Two pumps: local->remote and remote->local. Closing either tears down both.
        startPump("jukz-relay-up", local.getInputStream(), remote.outputStream(), local, remote)
        startPump("jukz-relay-down", remote.inputStream(), local.getOutputStream(), local, remote)
    }

    private fun startPump(
        name: String,
        input: java.io.InputStream,
        output: java.io.OutputStream,
        local: Socket,
        remote: JukzChannel,
    ) {
        val t = Thread({
            val buf = ByteArray(bufferSize)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
                // fall through to teardown
            } finally {
                runCatching { local.close() }
                remote.close()
            }
        }, name).apply { isDaemon = true }
        pumps.add(t)
        t.start()
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
    }
}
