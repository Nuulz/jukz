package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InMemoryWorldRegistryTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private fun record(gen: Long, nodeLast: Int = 1, hb: Long = 0) =
        WorldRecord(world, ClaimToken(gen, 0, node(nodeLast)), Endpoint("h", 1000), hb)

    @Test
    fun `first publish succeeds`() = runBlocking {
        val reg = InMemoryWorldRegistry(FakeClock(0))
        val r = reg.publishIfNewer(record(1))
        assertInstanceOf(PublishResult.Published::class.java, r)
        assertEquals(record(1), reg.lookup(world))
    }

    @Test
    fun `lower or equal token is rejected with current`() = runBlocking {
        val reg = InMemoryWorldRegistry(FakeClock(0))
        reg.publishIfNewer(record(5))
        val lower = reg.publishIfNewer(record(4))
        val equal = reg.publishIfNewer(record(5))
        assertInstanceOf(PublishResult.Rejected::class.java, lower)
        assertInstanceOf(PublishResult.Rejected::class.java, equal)
        assertEquals(record(5), (lower as PublishResult.Rejected).current)
    }

    @Test
    fun `strictly higher token overwrites`() = runBlocking {
        val reg = InMemoryWorldRegistry(FakeClock(0))
        reg.publishIfNewer(record(5))
        val higher = reg.publishIfNewer(record(6))
        assertInstanceOf(PublishResult.Published::class.java, higher)
        assertEquals(6, reg.lookup(world)!!.hostGeneration)
    }

    @Test
    fun `withdraw only removes a matching token`() = runBlocking {
        val reg = InMemoryWorldRegistry(FakeClock(0))
        val rec = record(5)
        reg.publishIfNewer(rec)
        reg.withdraw(world, ClaimToken(9, 0, node(1))) // non-matching: no-op
        assertEquals(rec, reg.lookup(world))
        reg.withdraw(world, rec.token) // matching: removed
        assertNull(reg.lookup(world))
    }

    @Test
    fun `heartbeat refreshes matching record and resets ttl`() = runBlocking {
        val clock = FakeClock(0)
        val reg = InMemoryWorldRegistry(clock, ttlMs = 1_000)
        reg.publishIfNewer(record(5, hb = 0))
        clock.advance(900)
        val ok = reg.heartbeat(record(5, hb = 1))
        assertEquals(true, ok)
        assertEquals(1, reg.lookup(world)!!.heartbeatSeq)
        clock.advance(999) // would have expired at 1000 without the refresh at 900
        assertEquals(1, reg.lookup(world)!!.heartbeatSeq)
    }

    @Test
    fun `heartbeat fails when token does not match`() = runBlocking {
        val reg = InMemoryWorldRegistry(FakeClock(0))
        reg.publishIfNewer(record(5))
        assertEquals(false, reg.heartbeat(record(6)))
    }

    @Test
    fun `record expires after ttl`() = runBlocking {
        val clock = FakeClock(0)
        val reg = InMemoryWorldRegistry(clock, ttlMs = 1_000)
        reg.publishIfNewer(record(1))
        clock.advance(999)
        assertEquals(record(1), reg.lookup(world))
        clock.advance(1) // now at ttl boundary -> expired
        assertNull(reg.lookup(world))
    }
}
