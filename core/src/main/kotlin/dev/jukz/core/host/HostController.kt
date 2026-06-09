package dev.jukz.core.host

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
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
 * via a [LanOpener], resolves a reachable [dev.jukz.core.model.Endpoint] through an
 * [EndpointResolver], publishes the discovery [WorldRecord] under a fencing [ClaimToken], and keeps
 * it alive with periodic heartbeats. The fencing/CAS decisions live in the [WorldRegistry]; this
 * class only drives the open → publish → heartbeat → withdraw lifecycle.
 *
 * Control/data transport is the guest's concern ([dev.jukz.core.join.JoinController]); a real host
 * answers those once the live network adapters are wired. Against [dev.jukz.core.discovery
 * .InMemoryWorldRegistry] the published record is real but only locally visible, so this is fully
 * deterministic to unit-test while the live DHT is still flagged.
 *
 * The fencing [generation] is incremented (and persisted) by the caller before [host] is invoked,
 * keeping Minecraft persistence out of `core`.
 */
class HostController(
    private val registry: WorldRegistry,
    private val lanOpener: LanOpener,
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
        val port = lanOpener.open() ?: return HostResult.Failed("could not open local port")
        return try {
            val endpoint = endpointResolver.resolve(port)
            val token = ClaimToken(generation, clock.nowMillis(), nodeId)
            val candidate = WorldRecord(worldId, token, endpoint, heartbeatSeq = 0)
            when (val result = registry.publishIfNewer(candidate)) {
                is PublishResult.Published -> {
                    record = result.record
                    startHeartbeat(worldId)
                    HostResult.Hosting(worldId.shortCode(), port)
                }
                is PublishResult.Rejected -> HostResult.Superseded(result.current)
            }
        } catch (e: Exception) {
            HostResult.Failed(e.message ?: e.toString())
        }
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

    private fun startHeartbeat(worldId: WorldId) {
        heartbeatJob = scope.launch {
            try {
                while (true) {
                    delay(config.heartbeatIntervalMs)
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
        val current = record ?: return
        record = null
        runCatching { registry.withdraw(current.worldId, current.token) }
    }

    override fun close() {
        runCatching { runBlocking { stop() } }
        runCatching { scope.cancel() }
    }
}
