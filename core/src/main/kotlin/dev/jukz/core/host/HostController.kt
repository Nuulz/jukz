package dev.jukz.core.host

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.JukzClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong

/**
 * Host side of the world-sharing flow (spec 2026-06-09-host). Opens the local world to the network
 * via a [LanOpener], starts a [ConnectionServer] that answers guests' jukz handshakes and pipes their
 * data to the local game, resolves the server's reachable [Endpoint] through an [EndpointResolver],
 * publishes the discovery [WorldRecord] under a fencing [ClaimToken], and keeps it alive with
 * periodic heartbeats. The fencing/CAS decisions live in the [WorldRegistry]; this class drives the
 * open → serve → publish → heartbeat → withdraw lifecycle.
 *
 * The guest side ([dev.jukz.core.join.JoinController]) connects to the announced endpoint and is
 * answered by the [ConnectionServer] — the host is no longer a test double. Against
 * [dev.jukz.core.discovery.InMemoryWorldRegistry] with a loopback [EndpointResolver] the whole path
 * is exercisable in-process, so it is fully deterministic to unit-test while the live DHT/NAT
 * adapters are still flagged.
 *
 * The fencing [generation] is incremented (and persisted) by the caller before [host] is invoked,
 * keeping Minecraft persistence out of `core`.
 */
class HostController(
    private val registry: WorldRegistry,
    private val lanOpener: LanOpener,
    private val connectionServer: ConnectionServer,
    private val endpointResolver: EndpointResolver,
    private val nodeId: NodeId,
    private val clock: JukzClock,
    private val config: HostConfig = HostConfig(),
    /** Invoked if the heartbeat stops succeeding (record expired or a newer host took over). */
    private val onHostLost: (WorldId) -> Unit = {},
) : AutoCloseable {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val heartbeatSeq = AtomicLong(0)

    @Volatile private var record: WorldRecord? = null
    @Volatile private var heartbeatJob: Job? = null

    /** Run the full host-open. Safe to call once per controller; build a new one to re-host. */
    suspend fun host(worldId: WorldId, generation: Long): HostResult {
        val gamePort = lanOpener.open() ?: return HostResult.Failed("could not open local port")
        return try {
            val token = ClaimToken(generation, clock.nowMillis(), nodeId)
            // The connection server fronts the local game; the announced endpoint is its listen port.
            val listenPort = connectionServer.start(worldId, token, Endpoint("127.0.0.1", gamePort)) {
                record?.heartbeatSeq ?: 0L
            }
            val endpoint = endpointResolver.resolve(listenPort)
            val candidate = WorldRecord(worldId, token, listOf(endpoint), heartbeatSeq = 0)
            when (val result = registry.publishIfNewer(candidate)) {
                is PublishResult.Published -> {
                    record = result.record
                    startHeartbeat(worldId)
                    HostResult.Hosting(worldId.shortCode(), listenPort)
                }
                is PublishResult.Rejected -> {
                    connectionServer.close()
                    HostResult.Superseded(result.current)
                }
            }
        } catch (e: Exception) {
            connectionServer.close()
            HostResult.Failed(e.message ?: e.toString())
        }
    }

    /** The record we are currently announcing, or null when not hosting. Static info for the host UI. */
    val sharedRecord: WorldRecord? get() = record

    /**
     * Poll the registry to confirm our record is still the live, announced one. Returns null when not
     * hosting; otherwise [HostStatus.live] is true only when the registry holds our exact token. With
     * the in-memory registry this is the same process, so it reflects the heartbeat; with the live
     * DHT it is a real reachability/ownership check.
     */
    suspend fun status(): HostStatus? {
        val current = record ?: return null
        val found = registry.lookup(current.worldId)
        val live = found != null && found.token == current.token
        return HostStatus(live, found?.heartbeatSeq ?: current.heartbeatSeq, current.endpoints)
    }

    /**
     * One heartbeat tick: re-announce with an advanced sequence and reset the record's TTL. Returns
     * false when we are no longer the live host (superseded or expired) — the caller should stop.
     * The heartbeat loop calls this on [HostConfig.heartbeatIntervalMs]; it is also the unit of test.
     */
    suspend fun beat(): Boolean {
        val current = record ?: return false
        val next = current.withHeartbeat(heartbeatSeq.incrementAndGet())
        val refreshed = registry.heartbeat(next)
        if (refreshed) record = next
        return refreshed
    }

    /**
     * The delay between heartbeat ticks: a third of the registry's advertised lease TTL when it
     * has one (the rendezvous server publishes it on announce), else [HostConfig.heartbeatIntervalMs].
     */
    fun heartbeatDelayMs(): Long = registry.leaseTtlMs()?.let { it / 3 } ?: config.heartbeatIntervalMs

    private fun startHeartbeat(worldId: WorldId) {
        heartbeatJob = scope.launch {
            try {
                while (true) {
                    delay(heartbeatDelayMs())
                    if (!beat()) {
                        onHostLost(worldId)
                        return@launch
                    }
                }
            } catch (_: Exception) {
                // Scope cancelled on close() — stop quietly.
            }
        }
    }

    /** Stop heartbeating and withdraw the record (CAS on token, so a newer host is never clobbered). */
    suspend fun stop() {
        heartbeatJob?.let { runCatching { it.cancel() } }
        heartbeatJob = null
        runCatching { connectionServer.close() }
        val current = record ?: return
        record = null
        runCatching { registry.withdraw(current.worldId, current.token) }
    }

    override fun close() {
        runCatching { runBlocking { stop() } }
        runCatching { scope.cancel() }
    }
}
