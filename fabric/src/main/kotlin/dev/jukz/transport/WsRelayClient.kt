package dev.jukz.transport

import dev.jukz.JukzMod
import dev.jukz.core.discovery.RelayOffer
import dev.jukz.core.host.RelayRegistrar
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Host side of the relay reverse tunnel and a [RelayRegistrar]. On [register] it opens an outbound
 * control link (`wss://.../v1/relay/host?session=<id>`); each signalled nonce makes it open a work
 * conn (`/v1/relay/work?nonce=N`) and a loopback socket to the local connection server, pumping bytes
 * between them. The host's [dev.jukz.core.host.HostConnectionServer] is unchanged — it just receives
 * loopback connections that originated from a remote guest. Returns the [RelayOffer] to advertise, or
 * null when [shouldRegister] says the direct path already works (UPnP mapped) or no rendezvous is set.
 *
 * FLAGGED: real WebSocket I/O; validated in-game.
 */
class WsRelayClient(
    private val rendezvousBaseUrl: String?,
    private val shouldRegister: () -> Boolean,
) : RelayRegistrar {

    private val http: HttpClient = HttpClient.newHttpClient()
    @Volatile private var control: WebSocket? = null

    override fun register(listenPort: Int): RelayOffer? {
        val base = rendezvousBaseUrl ?: return null
        if (!shouldRegister()) {
            JukzMod.logger.info("jukz: UPnP mapped the port; not registering a relay session")
            return null
        }
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/')
        val sessionId = newSessionId()
        return try {
            control = http.newWebSocketBuilder()
                .buildAsync(URI.create("$wsBase/v1/relay/host?session=$sessionId"), HostListener(wsBase, listenPort))
                .get(15, TimeUnit.SECONDS)
            JukzMod.logger.info("jukz: registered relay session for non-UPnP host")
            RelayOffer(sessionId)
        } catch (e: Exception) {
            JukzMod.logger.warn("jukz: relay registration failed ({}); internet guests need UPnP/forward", e.message)
            null
        }
    }

    fun close() {
        runCatching { control?.abort() }
    }

    /** Reads work-conn signals (one nonce per text frame) and bridges each to the local game port. */
    private inner class HostListener(private val wsBase: String, private val listenPort: Int) : WebSocket.Listener {
        override fun onOpen(ws: WebSocket) {
            ws.request(1)
        }

        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            val nonce = data.toString().trim().toLongOrNull()
            if (nonce != null) {
                Thread({ openWorkConn(nonce) }, "jukz-relay-work").apply { isDaemon = true }.start()
            }
            ws.request(1)
            return null
        }

        private fun openWorkConn(nonce: Long) {
            val local = try {
                Socket().apply { connect(InetSocketAddress("127.0.0.1", listenPort), 4000) }
            } catch (e: Exception) {
                JukzMod.logger.info("jukz: relay work conn could not reach local game: {}", e.message); return
            }
            val workListener = object : WebSocket.Listener {
                override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    val bytes = ByteArray(data.remaining()); data.get(bytes)
                    runCatching { local.getOutputStream().write(bytes); local.getOutputStream().flush() }
                    ws.request(1)
                    return null
                }
                override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    runCatching { local.close() }; return null
                }
                override fun onError(ws: WebSocket, error: Throwable) {
                    runCatching { local.close() }
                }
            }
            val work = http.newWebSocketBuilder()
                .buildAsync(URI.create("$wsBase/v1/relay/work?nonce=$nonce"), workListener)
                .get(15, TimeUnit.SECONDS)
            // Pump local game bytes -> work conn.
            Thread({
                val buf = ByteArray(8 * 1024)
                try {
                    val input = local.getInputStream()
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        work.sendBinary(ByteBuffer.wrap(buf.copyOfRange(0, n)), true).get(15, TimeUnit.SECONDS)
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { work.sendClose(WebSocket.NORMAL_CLOSURE, "eof").get(5, TimeUnit.SECONDS) }
                    runCatching { local.close() }
                }
            }, "jukz-relay-pump").apply { isDaemon = true }.start()
        }
    }

    private fun newSessionId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
}
