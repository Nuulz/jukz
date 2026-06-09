package dev.jukz.core.join

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
    /** Invoked if the liveness monitor stops hearing pongs after a successful connect. */
    private val onHostLost: (WorldId) -> Unit = {},
) : AutoCloseable {

    private val nonces = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The raw channel is kept so the watchdog can close it to unblock a stuck read; `control`
    // is the single framed reader/writer over it (one per connection, reused across messages).
    @Volatile private var controlChannel: JukzChannel? = null
    @Volatile private var control: FramedMessageChannel? = null
    @Volatile private var relay: LocalTcpRelay? = null

    /** Run the full join. Safe to call once per controller; build a new one to retry. */
    suspend fun join(worldId: WorldId): JoinResult {
        val record = registry.lookup(worldId) ?: return JoinResult.HostUnavailable
        return try {
            runHandshake(worldId, record)
        } catch (e: Exception) {
            close()
            JoinResult.Failed(e.message ?: e.toString())
        }
    }

    private suspend fun runHandshake(worldId: WorldId, record: WorldRecord): JoinResult {
        var endpoint = record.endpoint
        val sm = JoinerStateMachine(worldId, record.token, endpoint)
        sm.begin()

        openControl(endpoint).send(hello(worldId, record.token))

        while (true) {
            val msg = receiveOrTimeout(config.handshakeMs)
                ?: return ghost(sm, worldId) // no reply in time -> treat host as a ghost
            when (val action = sm.onMessage(msg).action) {
                is JoinerAction.Connect -> return handoff(worldId, record, action.endpoint, sm)
                is JoinerAction.FollowRedirect -> {
                    closeControl()
                    endpoint = action.endpoint
                    openControl(endpoint).send(hello(worldId, record.token))
                }
                JoinerAction.Takeover -> return JoinResult.ShouldHost(worldId)
                JoinerAction.Wait -> Unit // non-terminal; keep reading
            }
        }
    }

    private fun ghost(sm: JoinerStateMachine, worldId: WorldId): JoinResult {
        sm.onTimeout()
        return JoinResult.ShouldHost(worldId)
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
        startLiveness(worldId, record.token)
        return JoinResult.Connected(RELAY_HOST, port)
    }

    /** Best-effort liveness monitor: ping the control channel; on loss, notify and stop. */
    private fun startLiveness(worldId: WorldId, token: ClaimToken) {
        scope.launch {
            try {
                while (true) {
                    delay(config.livenessIntervalMs)
                    val channel = control ?: return@launch
                    channel.send(Message.Ping(worldId, token, nextNonce()))
                    if (receiveOrTimeout(config.livenessTimeoutMs) == null) {
                        onHostLost(worldId)
                        return@launch
                    }
                }
            } catch (_: Exception) {
                // Channel closed or scope cancelled — stop quietly.
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
        runCatching { scope.cancel() }
        relay?.let { runCatching { it.close() } }
        relay = null
        closeControl()
    }

    companion object {
        const val RELAY_HOST = "127.0.0.1"
    }
}
