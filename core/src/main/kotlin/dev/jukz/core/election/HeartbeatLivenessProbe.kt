package dev.jukz.core.election

import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.util.JukzClock
import kotlinx.coroutines.delay

/**
 * Confirms a host is live by observing its [WorldRecord.heartbeatSeq] advance in the registry
 * within [ElectionConfig.livenessTimeoutMs] (R10/R11). Deliberately does NOT treat a failed
 * TCP connect as the sole death signal: a live-but-NAT-unreachable host must not be taken over,
 * or two real hosts could coexist (split-brain across a NAT boundary).
 */
class HeartbeatLivenessProbe(
    private val registry: WorldRegistry,
    @Suppress("unused") private val clock: JukzClock,
    private val config: ElectionConfig = ElectionConfig(),
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : HostElection.LivenessProbe {

    override suspend fun isAlive(record: WorldRecord): Boolean {
        val baselineSeq = record.heartbeatSeq
        sleeper(config.livenessTimeoutMs)
        val latest = registry.lookup(record.worldId) ?: return false
        // A different token means another host superseded this one (or it expired and was replaced).
        if (latest.token != record.token) return false
        return latest.heartbeatSeq > baselineSeq
    }
}
