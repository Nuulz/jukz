package dev.jukz.core.host

import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.handshake.FramedMessageChannel
import dev.jukz.core.handshake.HostStateMachine
import dev.jukz.core.handshake.Message
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.SocketChannel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real host-side listener — the production counterpart of the join flow's test double. Binds a
 * socket and, per inbound jukz connection, reads the leading [ConnectionType] byte and routes it:
 *  - CONTROL: drives a [HostStateMachine] (handshake + fencing + Ping/Pong liveness) over a
 *    [FramedMessageChannel].
 *  - DATA: a transparent byte pipe to the locally-opened Minecraft server ([gameEndpoint], typically
 *    `127.0.0.1:<openToLan port>`).
 *
 * The announced [dev.jukz.core.discovery.WorldRecord] endpoint points at this listener (after NAT
 * mapping), so a guest's relay reaches the game through here. Minecraft's bytes stay opaque — the
 * DATA path is a dumb pump, so the client's end-to-end encryption and online-mode auth are preserved.
 */
class HostConnectionServer(
    private val bindHost: String = "0.0.0.0",
    private val connectGameTimeoutMs: Int = 4_000,
    private val bufferSize: Int = 8 * 1024,
) : ConnectionServer {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var session: Session? = null
    private val pumps = CopyOnWriteArrayList<Thread>()
    private val controlChannels = CopyOnWriteArrayList<FramedMessageChannel>()

    /** What a started server is serving (held in one nullable holder — [WorldId] is an inline class). */
    private class Session(
        val worldId: WorldId,
        val token: ClaimToken,
        val gameEndpoint: Endpoint,
        val heartbeatSeq: () -> Long,
    )

    override fun start(worldId: WorldId, token: ClaimToken, gameEndpoint: Endpoint, heartbeatSeq: () -> Long): Int {
        session = Session(worldId, token, gameEndpoint, heartbeatSeq)
        val s = ServerSocket()
        s.bind(InetSocketAddress(bindHost, 0))
        server = s
        running = true
        Thread({ acceptLoop(s) }, "jukz-host-accept").apply { isDaemon = true }.start()
        return s.localPort
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val sock = try {
                s.accept()
            } catch (_: Exception) {
                if (running) continue else break
            }
            Thread({ handle(sock) }, "jukz-host-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(sock: Socket) {
        val sess = session ?: run { runCatching { sock.close() }; return }
        runCatching {
            val ch = SocketChannel(sock)
            when (ConnectionType.readFrom(ch)) {
                ConnectionType.CONTROL -> serveControl(ch, sess)
                ConnectionType.DATA -> pipeToGame(sock, sess)
            }
        }.onFailure { runCatching { sock.close() } }
    }

    /** Drive the host handshake state machine over a CONTROL channel until it closes or yields. */
    private fun serveControl(ch: JukzChannel, sess: Session) {
        val framed = FramedMessageChannel(ch)
        val sm = HostStateMachine(sess.worldId, sess.token, sess.heartbeatSeq)
        controlChannels.add(framed) // tracked so we can push a HostLeaving when we withdraw
        try {
            while (running) {
                val msg = try {
                    framed.receive()
                } catch (_: Exception) {
                    return
                }
                val reaction = sm.onMessage(msg)
                reaction.reply?.let { runCatching { framed.send(it) } }
                if (reaction.shutdown) {
                    runCatching { ch.close() }
                    return
                }
            }
        } finally {
            controlChannels.remove(framed)
        }
    }

    override fun connectedGuestCount(): Int = controlChannels.size

    override fun notifyGuestsLeaving(snapshot: SnapshotOffer?) {
        val sess = session ?: return
        val msg = Message.HostLeaving(sess.worldId, sess.token, 0, snapshot)
        controlChannels.forEach { runCatching { it.send(msg) } } // send is thread-safe (synchronized output)
    }

    /** Pipe a DATA channel transparently to the local game server, both directions. */
    private fun pipeToGame(incoming: Socket, sess: Session) {
        val game = try {
            Socket().apply { connect(InetSocketAddress(sess.gameEndpoint.host, sess.gameEndpoint.port), connectGameTimeoutMs) }
        } catch (_: Exception) {
            runCatching { incoming.close() }
            return
        }
        startPump(incoming.getInputStream(), game.getOutputStream(), incoming, game)
        startPump(game.getInputStream(), incoming.getOutputStream(), incoming, game)
    }

    private fun startPump(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
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
                runCatching { a.close() }
                runCatching { b.close() }
            }
        }, "jukz-host-pump").apply { isDaemon = true }
        pumps.add(t)
        t.start()
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
        // Close active control channels so connected guests see the drop immediately (abrupt-leave path).
        controlChannels.forEach { runCatching { it.close() } }
        controlChannels.clear()
    }
}
