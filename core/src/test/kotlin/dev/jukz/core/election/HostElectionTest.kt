package dev.jukz.core.election

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostElectionTest {

    private val world = WorldId.random()
    private val myEndpoint = Endpoint("me", 25565)
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })

    private val alive = HostElection.LivenessProbe { true }
    private val ghost = HostElection.LivenessProbe { false }

    private class Fixture {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock, ttlMs = 30 * 60 * 1000)
        val election = HostElection(registry, clock, ElectionConfig()) { clock.advance(it) }
    }

    @Test
    fun `uncontested claim becomes host`() = runBlocking {
        val f = Fixture()
        val outcome = f.election.elect(world, proposedGeneration = 1, node(1), myEndpoint, ghost)
        assertInstanceOf(ElectionOutcome.BecameHost::class.java, outcome)
        assertEquals(1, (outcome as ElectionOutcome.BecameHost).record.hostGeneration)
    }

    @Test
    fun `live host makes us a guest (R4)`() = runBlocking {
        val f = Fixture()
        val hostEp = Endpoint("host", 30000)
        f.registry.publishIfNewer(WorldRecord(world, ClaimToken(5, 0, node(9)), hostEp, 0))
        val outcome = f.election.elect(world, proposedGeneration = 1, node(1), myEndpoint, alive)
        assertInstanceOf(ElectionOutcome.BecameGuest::class.java, outcome)
        assertEquals(hostEp, (outcome as ElectionOutcome.BecameGuest).redirect)
    }

    @Test
    fun `ghost host is fenced and taken over with generation plus one (R12)`() = runBlocking {
        val f = Fixture()
        f.registry.publishIfNewer(WorldRecord(world, ClaimToken(5, 0, node(9)), Endpoint("host", 30000), 0))
        val outcome = f.election.elect(world, proposedGeneration = 1, node(1), myEndpoint, ghost)
        assertInstanceOf(ElectionOutcome.BecameHost::class.java, outcome)
        assertEquals(6, (outcome as ElectionOutcome.BecameHost).record.hostGeneration)
    }

    @Test
    fun `loser cedes to a higher live token (R7)`() = runBlocking {
        val f = Fixture()
        val winnerEp = Endpoint("winner", 31000)
        // A competitor with the same generation but a higher token, and alive.
        f.registry.publishIfNewer(WorldRecord(world, ClaimToken(2, 500, node(9)), winnerEp, 0))
        val outcome = f.election.elect(world, proposedGeneration = 2, node(1), myEndpoint, alive)
        assertInstanceOf(ElectionOutcome.BecameGuest::class.java, outcome)
        assertEquals(winnerEp, (outcome as ElectionOutcome.BecameGuest).redirect)
    }

    @Test
    fun `validateIncoming fences stale generations (R14)`() {
        val f = Fixture()
        assertFalse(f.election.validateIncoming(myGeneration = 5, ClaimToken(4, 0, node(1))))
        assertTrue(f.election.validateIncoming(myGeneration = 5, ClaimToken(5, 0, node(1))))
        assertTrue(f.election.validateIncoming(myGeneration = 5, ClaimToken(6, 0, node(1))))
    }
}
