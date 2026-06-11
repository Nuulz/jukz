package dev.jukz.transport

import dev.jukz.JukzMod
import dev.jukz.core.transport.JukzChannel
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Opens a [JukzChannel] to the rendezvous relay for a host's session, over a binary WebSocket on the
 * same HTTPS origin as the rendezvous (`wss://.../v1/relay/connect?session=<id>`). Incoming binary
 * frames are funnelled into a [PipedInputStream] the join flow reads; bytes written to the channel's
 * [OutputStream] are sent as binary frames. This is the guest side of the reverse tunnel — selected
 * by the connect ladder only after every direct endpoint fails.
 *
 * FLAGGED: real WebSocket I/O against the live relay; validated in-game, not unit-tested.
 */
class WsRelayTransport(rendezvousBaseUrl: String) {

    private val wsBase = rendezvousBaseUrl
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/')
    private val http: HttpClient = HttpClient.newHttpClient()

    /** Connect a channel to [sessionId]; blocks until the WS handshake completes. */
    fun connect(sessionId: String): JukzChannel {
        JukzMod.logger.info("jukz: dialing host via relay (session {})", sessionId)
        val toApp = PipedOutputStream()
        val appIn = PipedInputStream(toApp, 64 * 1024)

        val listener = object : WebSocket.Listener {
            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining()); data.get(bytes)
                runCatching { toApp.write(bytes); toApp.flush() }
                ws.request(1)
                return null
            }
            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                runCatching { toApp.close() }
                return null
            }
            override fun onError(ws: WebSocket, error: Throwable) {
                JukzMod.logger.info("jukz: relay WS error: {}", error.message)
                runCatching { toApp.close() }
            }
        }

        val ws = http.newWebSocketBuilder()
            .buildAsync(URI.create("$wsBase/v1/relay/connect?session=$sessionId"), listener)
            .get(15, TimeUnit.SECONDS)

        val appOut = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val chunk = ByteBuffer.wrap(b.copyOfRange(off, off + len))
                ws.sendBinary(chunk, true).get(15, TimeUnit.SECONDS)
            }
            override fun close() {
                runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS) }
            }
        }

        return object : JukzChannel {
            override fun inputStream(): InputStream = appIn
            override fun outputStream(): OutputStream = appOut
            override fun close() {
                runCatching { appOut.close() }
                runCatching { appIn.close() }
                ws.abort()
            }
        }
    }
}
