package dev.jukz.core.host

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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Deterministic host-flow tests, mirroring the loopback/InMemory style of the join tests. The
 * time-based heartbeat loop is not driven here; instead [HostController.beat] (the single tick the
 * loop calls) is exercised directly with a [FakeClock], so re-announce/expiry behaviour is provable
 * without real sleeps.
 */
class HostControllerTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private val nodeId = node(7)

    /** A [LanOpener] that always succeeds with a fixed port. */
    private fun opener(port: Int) = LanOpener { port }

    /** A loopback-style resolver: the guest would dial 127.0.0.1:<port>. */
    private val resolver = EndpointResolver { port -> Endpoint("127.0.0.1", port) }

    private fun controller(
        registry: InMemoryWorldRegistry,
        clock: FakeClock,
        lan: LanOpener = opener(54321),
    ) = HostController(registry, lan, resolver, nodeId, clock)

    @Test
    fun `opens and publishes a live record`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val host = controller(registry, clock, opener(45678))

        val result = host.host(world, generation = 3)

        val hosting = assertInstanceOf(HostResult.Hosting::class.java, result)
        assertEquals(world.shortCode(), hosting.shortCode)
        assertEquals(45678, hosting.port)

        val record = registry.lookup(world)
        assertNotNull(record)
        assertEquals(3, record!!.token.hostGeneration)
        assertEquals(nodeId, record.token.nodeId)
        assertEquals(Endpoint("127.0.0.1", 45678), record.endpoint)

        host.close()
    }

    @Test
    fun `reports Superseded when a newer host already owns the world`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val incumbent = WorldRecord(world, ClaimToken(9, 500, node(2)), Endpoint("10.0.0.1", 25565), 0)
        registry.publishIfNewer(incumbent)

        val result = controller(registry, clock).host(world, generation = 3)

        val superseded = assertInstanceOf(HostResult.Superseded::class.java, result)
        assertEquals(incumbent, superseded.current)
        // The incumbent's record is left untouched.
        assertEquals(incumbent, registry.lookup(world))
    }

    @Test
    fun `heartbeat re-announce keeps the record alive past the original TTL`() = runBlocking {
        val clock = FakeClock(0)
        val registry = InMemoryWorldRegistry(clock, ttlMs = 1_000)
        val host = controller(registry, clock)

        host.host(world, generation = 1)

        // Approach the TTL, then re-announce; this resets publishedAt to "now".
        clock.advance(900)
        assertTrue(host.beat())

        // Past the original TTL (now 1_700 > 1_000) but within one TTL of the heartbeat.
        clock.advance(800)
        val record = registry.lookup(world)
        assertNotNull(record)
        assertEquals(1L, record!!.heartbeatSeq) // advanced from 0 on the first beat

        host.close()
    }

    @Test
    fun `beat reports loss once a newer host takes over`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val host = controller(registry, clock)
        host.host(world, generation = 1)

        // A competitor publishes a strictly higher token (higher generation).
        registry.publishIfNewer(WorldRecord(world, ClaimToken(2, 1_000, node(1)), Endpoint("10.0.0.2", 25565), 0))

        assertFalse(host.beat())
        host.close()
    }

    @Test
    fun `stop withdraws the record`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val host = controller(registry, clock)
        host.host(world, generation = 1)
        assertNotNull(registry.lookup(world))

        host.stop()

        assertNull(registry.lookup(world))
    }

    @Test
    fun `reports Failed when the world cannot be opened`() = runBlocking {
        val clock = FakeClock(1_000)
        val registry = InMemoryWorldRegistry(clock)
        val host = HostController(registry, LanOpener { null }, resolver, nodeId, clock)

        val result = host.host(world, generation = 1)

        assertInstanceOf(HostResult.Failed::class.java, result)
        assertNull(registry.lookup(world))
    }
}
