package dev.jukz.core.join

import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.discovery.dialTargets
import dev.jukz.core.handshake.FramedMessageChannel
import dev.jukz.core.handshake.HandshakeRole
import dev.jukz.core.handshake.JoinerAction
import dev.jukz.core.handshake.JoinerStateMachine
import dev.jukz.core.handshake.Message
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.transport.ConnectionType
import dev.jukz.core.transport.DialTarget
import dev.jukz.core.transport.JukzChannel
import dev.jukz.core.transport.LocalTcpRelay
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
    private val dialer: ChannelDialer,
    private val gameHandoff: GameHandoff,
    @Suppress("unused") private val clock: JukzClock,
    private val config: JoinConfig = JoinConfig(),
    /**
     * Invoked once when the host goes away after a successful connect: either it sent a
     * [Message.HostLeaving] (clean handoff — the [SnapshotOffer] carries the gate token) or the control
     * channel broke (abrupt drop — offer is null). The [DialTarget] is how this guest reached the host
     * (direct endpoint or relay session); the caller pulls the snapshot over that same path. The caller
     * takes over hosting.
     */
    private val onHostLost: (WorldId, SnapshotOffer?, DialTarget?) -> Unit = { _, _, _ -> },
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
        for (target in record.dialTargets()) {
            try {
                return runHandshake(worldId, record, target)
            } catch (e: Exception) {
                closeControl() // tear down this attempt only; the next target gets a fresh channel
                lastFailure = e
            }
        }
        close()
        return JoinResult.Failed(lastFailure?.message ?: "no reachable endpoint")
    }

    private suspend fun runHandshake(worldId: WorldId, record: WorldRecord, target: DialTarget): JoinResult {
        var current = target
        val sm = JoinerStateMachine(worldId, record.token, endpointOf(current))
        sm.begin()

        openControl(current).send(hello(worldId, record.token))

        while (true) {
            val msg = receiveOrTimeout(config.handshakeMs)
                ?: return ghost(sm, record) // no reply in time -> treat host as a ghost
            when (val action = sm.onMessage(msg).action) {
                // Keep the data channel on whatever path control connected over: a relay session must
                // pull DATA through the relay too (the host's Connect endpoint is its own loopback/LAN
                // address, which an internet guest cannot dial directly).
                is JoinerAction.Connect -> {
                    val dataTarget = if (current is DialTarget.ViaRelay) current else DialTarget.Direct(action.endpoint)
                    return handoff(worldId, record, dataTarget, sm)
                }
                is JoinerAction.FollowRedirect -> {
                    closeControl()
                    current = DialTarget.Direct(action.endpoint)
                    openControl(current).send(hello(worldId, record.token))
                }
                JoinerAction.Takeover -> return JoinResult.ShouldHost(worldId, record)
                JoinerAction.Wait -> Unit // non-terminal; keep reading
            }
        }
    }

    /** The endpoint a target presents to the state machine; a relay target has no dial address, so a synthetic placeholder stands in for its fencing/redirect bookkeeping. */
    private fun endpointOf(target: DialTarget): Endpoint = when (target) {
        is DialTarget.Direct -> target.endpoint
        is DialTarget.ViaRelay -> SYNTHETIC_RELAY_ENDPOINT
    }

    private fun ghost(sm: JoinerStateMachine, record: WorldRecord): JoinResult {
        sm.onTimeout()
        return JoinResult.ShouldHost(record.worldId, record)
    }

    private fun handoff(
        worldId: WorldId,
        record: WorldRecord,
        target: DialTarget,
        sm: JoinerStateMachine,
    ): JoinResult {
        val relay = LocalTcpRelay(openRemote = {
            runBlocking { dialer.dial(target) }.also { ConnectionType.DATA.writeTo(it) }
        })
        val port = relay.start()
        this.relay = relay
        gameHandoff.connect(RELAY_HOST, port)
        sm.markConnected()
        startLiveness(worldId, record.token, target)
        return JoinResult.Connected(RELAY_HOST, port)
    }

    /**
     * Watch the control channel after connecting. A [Message.HostLeaving] is the host handing off (take
     * over with its snapshot offer); a broken channel is the host dropping abruptly (take over with the
     * local copy). A separate pinger nudges the host so the link stays observed. Fires [onHostLost] once.
     *
     * [target] is how this guest reached the host (a direct endpoint or a relay session). The handoff
     * snapshot is served on the same connection-server port the game uses, so the caller pulls it over
     * this exact path — not the host's advertised snapshot host/port, which may be a LAN address (or a
     * relay-only host) an internet guest can't dial directly. The gate token from the offer is what the
     * caller keeps; "where" is "the way I'm already connected".
     */
    private fun startLiveness(worldId: WorldId, token: ClaimToken, target: DialTarget) {
        // Reader: block on inbound control messages until a handoff notice or a broken channel.
        scope.launch {
            while (true) {
                val framed = control ?: return@launch
                val msg = try {
                    framed.receive()
                } catch (_: Exception) {
                    if (!closing) onHostLost(worldId, null, target) // channel broke -> host gone abruptly, no offer
                    return@launch
                }
                if (msg is Message.HostLeaving) {
                    onHostLost(worldId, msg.snapshot, target) // pull over the same path we reached the host on
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

    private suspend fun openControl(target: DialTarget): FramedMessageChannel {
        val ch = dialer.dial(target)
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
        private val SYNTHETIC_RELAY_ENDPOINT = Endpoint("relay", 1)
    }
}
