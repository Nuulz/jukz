package dev.jukz.core.election

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.JukzClock
import kotlinx.coroutines.delay

/**
 * The serverless host-election protocol (spec §4.4, rules R1-R14).
 *
 * Lease + monotonic fencing token: a node may host only with a token whose [ClaimToken.hostGeneration]
 * strictly fences any record it has seen. Enforcement is resource-side — the registry CAS
 * ([WorldRegistry.publishIfNewer]) rejects stale tokens, and every node rejects lower-generation
 * tokens at the handshake ([validateIncoming]). Client-only checks would not guarantee mutual
 * exclusion (Kleppmann/Hochstein).
 *
 * [sleeper] is injected so the settle window is deterministic in tests (advance a FakeClock
 * instead of really sleeping).
 */
class HostElection(
    private val registry: WorldRegistry,
    private val clock: JukzClock,
    private val config: ElectionConfig = ElectionConfig(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /** Whether an announced host is actually alive (heartbeatSeq advancing), per R10/R11. */
    fun interface LivenessProbe {
        suspend fun isAlive(record: WorldRecord): Boolean
    }

    /**
     * Decide whether this node hosts the world or joins a live host.
     *
     * @param proposedGeneration the local save generation already incremented for this host-open
     *        (persisted-monotonic, the fence). Election may raise it further to fence out a ghost.
     */
    suspend fun elect(
        worldId: WorldId,
        proposedGeneration: Long,
        nodeId: NodeId,
        myEndpoint: Endpoint,
        probe: LivenessProbe,
    ): ElectionOutcome {
        // R8/R4: if a live host already holds the world, join it as guest.
        val existing = registry.lookup(worldId)
        if (existing != null && probe.isAlive(existing)) {
            return ElectionOutcome.BecameGuest(existing.endpoint, existing)
        }

        // No live host, or the existing record is a ghost. Fence above anything seen (R12).
        var claimGen = maxOf(proposedGeneration, (existing?.hostGeneration ?: -1L) + 1L)

        repeat(config.maxDuelRounds) {
            val myToken = ClaimToken(claimGen, clock.nowMillis(), nodeId)
            val candidate = WorldRecord(worldId, myToken, myEndpoint, heartbeatSeq = 0L)

            when (val result = registry.publishIfNewer(candidate)) {
                is PublishResult.Published -> {
                    sleeper(config.announceSettleWindowMs) // R2: settle window
                    val after = registry.lookup(worldId)
                    if (after != null && after.token > myToken) {
                        // R4/R6: lost to a higher claim during settle.
                        if (probe.isAlive(after)) {
                            return ElectionOutcome.BecameGuest(after.endpoint, after)
                        }
                        // Higher but a ghost: fence above it and retry (R5).
                        claimGen = after.hostGeneration + 1L
                        return@repeat
                    }
                    return ElectionOutcome.BecameHost(candidate) // R3
                }

                is PublishResult.Rejected -> {
                    val current = result.current
                    if (probe.isAlive(current)) {
                        return ElectionOutcome.BecameGuest(current.endpoint, current) // R4
                    }
                    // Ghost holds a >= token: fence strictly above it and retry (R5/R12).
                    claimGen = current.hostGeneration + 1L
                    return@repeat
                }
            }
        }
        throw ElectionException("host election for $worldId did not converge in ${config.maxDuelRounds} rounds")
    }

    /**
     * R14: a node accepts an incoming claim/handshake token only if it is not stale relative
     * to its own authoritative generation. Returns true if acceptable, false if STALE_TOKEN.
     */
    fun validateIncoming(myGeneration: Long, incoming: ClaimToken): Boolean =
        incoming.hostGeneration >= myGeneration
}
