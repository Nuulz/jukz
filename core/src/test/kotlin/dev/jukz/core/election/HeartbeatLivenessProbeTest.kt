package dev.jukz.core.election

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeartbeatLivenessProbeTest {

    private val world = WorldId.random()
    private val token = ClaimToken(5, 0, NodeId.random())
    private val record = WorldRecord(world, token, Endpoint("host", 30000), heartbeatSeq = 0)

    @Test
    fun `host is alive when heartbeatSeq advances within the window (R10)`() = runBlocking {
        val clock = FakeClock(0)
        val reg = InMemoryWorldRegistry(clock, ttlMs = Long.MAX_VALUE / 4)
        reg.publishIfNewer(record)
        // During the liveness wait, the host re-announces with an advanced heartbeat.
        val probe = HeartbeatLivenessProbe(reg, clock, ElectionConfig()) { ms ->
            clock.advance(ms)
            reg.heartbeat(record.withHeartbeat(1))
        }
        assertTrue(probe.isAlive(record))
    }

    @Test
    fun `host is a ghost when heartbeatSeq does not advance (R11)`() = runBlocking {
        val clock = FakeClock(0)
        val reg = InMemoryWorldRegistry(clock, ttlMs = Long.MAX_VALUE / 4)
        reg.publishIfNewer(record)
        // The host is gone: the wait elapses with no re-announce.
        val probe = HeartbeatLivenessProbe(reg, clock, ElectionConfig()) { ms -> clock.advance(ms) }
        assertFalse(probe.isAlive(record))
    }
}
