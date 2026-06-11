package dev.jukz.core.join

import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.handshake.FramedMessageChannel
import dev.jukz.core.handshake.HandshakeRole
import dev.jukz.core.handshake.JoinerAction
import dev.jukz.core.handshake.JoinerStateMachine
import dev.jukz.core.handshake.Message
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.LocalTcpRelay
import dev.jukz.core.transport.Transport
import dev.jukz.core.util.JukzClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guest side of the join flow (spec 2026-06-09). Looks a world up, validates the announced host
 * over a jukz control channel (handshake + fencing), stands up a [LocalTcpRelay] for the data path,
 * and hands the vanilla client off to it. The deterministic decision-making lives in
 * [JoinerStateMachine]; this class only pumps I/O to and from it.
 *
 * Control and data channels are separate (decision 2): the control channel carries the handshake
 * and liveness pings; the relay opens fresh data channels as transparent pipes. A leading
 * [ConnectionType] byte lets the host route each one.
 */
class JoinController(
    private val registry: WorldRegistry,
    private val transport: Transport,
    private val gameHandoff: GameHandoff,
    @Suppress("unused") private val clock: JukzClock,
    private val config: JoinConfig = JoinConfig(),
    /**
     * Invoked once when the host goes away after a successful connect: either it sent a
     * [Message.HostLeaving] (clean handoff — the [SnapshotOffer] is where to pull its save from) or the
     * control channel broke (abrupt drop — offer is null). The caller takes over hosting.
     */
    private val onHostLost: (WorldId, SnapshotOffer?) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val nonces = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var closing = false

    // The raw channel is kept so the watchdog can close it to unblock a stuck read; `control`
    // is the single framed reader/writer over it (one per connection, reused across messages).
    @Volatile private var controlChannel: JukzChannel? = null
    @Volatile private var control: FramedMessageChannel? = null
    @Volatile private var relay: LocalTcpRelay? = null

    /**
     * Run the full join. Safe to call once per controller; build a new one to retry.
     * The record's endpoints are candidates tried in order (LAN address first, then the
     * rendezvous-observed public address); the first one that completes a handshake wins.
     */
    suspend fun join(worldId: WorldId): JoinResult {
        val record = registry.lookup(worldId) ?: return JoinResult.HostUnavailable
        var lastFailure: Exception? = null
        for (candidate in record.endpoints) {
            try {
                return runHandshake(worldId, record, candidate)
            } catch (e: Exception) {
                closeControl() // tear down this attempt only; the next candidate gets a fresh channel
                lastFailure = e
            }
        }
        close()
        return JoinResult.Failed(lastFailure?.message ?: "no reachable endpoint")
    }

    private suspend fun runHandshake(worldId: WorldId, record: WorldRecord, candidate: Endpoint): JoinResult {
        var endpoint = candidate
        val sm = JoinerStateMachine(worldId, record.token, endpoint)
        sm.begin()

        openControl(endpoint).send(hello(worldId, record.token))

        while (true) {
            val msg = receiveOrTimeout(config.handshakeMs)
                ?: return ghost(sm, record) // no reply in time -> treat host as a ghost
            when (val action = sm.onMessage(msg).action) {
                is JoinerAction.Connect -> return handoff(worldId, record, action.endpoint, sm)
                is JoinerAction.FollowRedirect -> {
                    closeControl()
                    endpoint = action.endpoint
                    openControl(endpoint).send(hello(worldId, record.token))
                }
                JoinerAction.Takeover -> return JoinResult.ShouldHost(worldId, record)
                JoinerAction.Wait -> Unit // non-terminal; keep reading
            }
        }
    }

    private fun ghost(sm: JoinerStateMachine, record: WorldRecord): JoinResult {
        sm.onTimeout()
        return JoinResult.ShouldHost(record.worldId, record)
    }

    private fun handoff(
        worldId: WorldId,
        record: WorldRecord,
        endpoint: Endpoint,
        sm: JoinerStateMachine,
    ): JoinResult {
        val relay = LocalTcpRelay(openRemote = {
            runBlocking { transport.connect(endpoint) }.also { ConnectionType.DATA.writeTo(it) }
        })
        val port = relay.start()
        this.relay = relay
        gameHandoff.connect(RELAY_HOST, port)
        sm.markConnected()
        startLiveness(worldId, record.token, endpoint)
        return JoinResult.Connected(RELAY_HOST, port)
    }

    /**
     * Watch the control channel after connecting. A [Message.HostLeaving] is the host handing off (take
     * over with its snapshot offer); a broken channel is the host dropping abruptly (take over with the
     * local copy). A separate pinger nudges the host so the link stays observed. Fires [onHostLost] once.
     *
     * [hostEndpoint] is the endpoint this guest actually reached. The handoff snapshot is served on that
     * same connection-server port, so we pull from there rather than from the host's advertised snapshot
     * host/port — which may be a LAN address an internet guest can't dial. The host's gate token is the
     * only part of its offer we keep; the location is "where I'm already connected".
     */
    private fun startLiveness(worldId: WorldId, token: ClaimToken, hostEndpoint: Endpoint) {
        // Reader: block on inbound control messages until a handoff notice or a broken channel.
        scope.launch {
            while (true) {
                val framed = control ?: return@launch
                val msg = try {
                    framed.receive()
                } catch (_: Exception) {
                    if (!closing) onHostLost(worldId, null) // channel broke -> host gone abruptly, no offer
                    return@launch
                }
                if (msg is Message.HostLeaving) {
                    val reachable = msg.snapshot?.copy(host = hostEndpoint.host, port = hostEndpoint.port)
                    onHostLost(worldId, reachable)
                    return@launch
                }
                // Pong / anything else -> host still alive; keep reading.
            }
        }
        // Pinger: keep the host answering; a failed send just means the reader will see the broken link.
        scope.launch {
            try {
                while (true) {
                    delay(config.livenessIntervalMs)
                    control?.send(Message.Ping(worldId, token, nextNonce())) ?: return@launch
                }
            } catch (_: Exception) {
                // send failed -> channel broken; the reader handles onHostLost.
            }
        }
    }

    private suspend fun openControl(endpoint: Endpoint): FramedMessageChannel {
        val ch = transport.connect(endpoint)
        ConnectionType.CONTROL.writeTo(ch)
        val framed = FramedMessageChannel(ch)
        controlChannel = ch
        control = framed
        return framed
    }

    private fun closeControl() {
        controlChannel?.let { runCatching { it.close() } }
        controlChannel = null
        control = null
    }

    /**
     * Read one framed message from the control channel, or null if none arrives within [ms].
     * The blocking read is made interruptible by a watchdog that closes the channel on timeout —
     * closing the socket unblocks `read()`. A timeout therefore also tears the control channel down,
     * which is the intended semantics: no reply in time means the host is gone.
     */
    private suspend fun receiveOrTimeout(ms: Long): Message? = withContext(Dispatchers.IO) {
        val ch = controlChannel ?: return@withContext null
        val framed = control ?: return@withContext null
        val watchdog = scope.launch {
            delay(ms)
            runCatching { ch.close() }
        }
        try {
            framed.receive()
        } catch (_: Exception) {
            null
        } finally {
            watchdog.cancel()
        }
    }

    private fun hello(worldId: WorldId, token: ClaimToken): Message =
        Message.Hello(worldId, token, nextNonce(), HandshakeRole.JOINER)

    private fun nextNonce(): Int = nonces.incrementAndGet()

    override fun close() {
        closing = true // so the reader doesn't mistake our own teardown for the host dropping
        runCatching { scope.cancel() }
        relay?.let { runCatching { it.close() } }
        relay = null
        closeControl()
    }

    companion object {
        const val RELAY_HOST = "127.0.0.1"
    }
}
