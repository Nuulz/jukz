package dev.jukz.discovery

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The composite's heartbeat verdict. The 2026-06-13 split-brain was permanent because a LAN-superseded
 * host kept getting `true` from this method: it returned only the rendezvous verdict, and the
 * rendezvous is optimistically `true` when the network is down. So a same-network host that a newer LAN
 * peer had already superseded never noticed, never withdrew, and kept serving + re-announcing forever.
 * The fix makes a genuine LAN supersession (a strictly-higher token holding the slot) report loss too,
 * while a transient LAN cache miss (no superseding token) must NOT self-evict a healthy host.
 */
class CompositeWorldRegistryTest {

    private val world = WorldId.random()
    private fun node(b: Int) = NodeId(ByteArray(16).also { it[15] = b.toByte() })
    private fun record(gen: Long, nodeLast: Int = 1, hb: Long = 0) =
        WorldRecord(world, ClaimToken(gen, 0, node(nodeLast)), Endpoint("h", 1000), hb)

    /** A rendezvous stand-in: [ok] models the optimistic verdict (true even when offline). */
    private class FakeRendezvous(var ok: Boolean = true) : WorldRegistry {
        override suspend fun publishIfNewer(record: WorldRecord) = PublishResult.Published(record)
        override suspend fun heartbeat(record: WorldRecord) = ok
        override suspend fun lookup(worldId: WorldId): WorldRecord? = null
        override suspend fun withdraw(worldId: WorldId, token: ClaimToken) {}
    }

    @Test
    fun `heartbeat reports loss when a strictly-higher LAN token holds the world, even if rendezvous is ok`() = runBlocking {
        val lan = InMemoryWorldRegistry(FakeClock(0))
        val composite = CompositeWorldRegistry(lan, FakeRendezvous(ok = true))
        val mine = record(gen = 40, nodeLast = 1)
        composite.publishIfNewer(mine)

        // A newer same-network host takes the slot (strictly-higher token).
        lan.publishIfNewer(record(gen = 41, nodeLast = 2))

        assertFalse(composite.heartbeat(mine.withHeartbeat(1)))
    }

    @Test
    fun `heartbeat stays alive on a transient LAN cache miss while rendezvous is ok`() = runBlocking {
        val clock = FakeClock(0)
        val lan = InMemoryWorldRegistry(clock, ttlMs = 1_000)
        val composite = CompositeWorldRegistry(lan, FakeRendezvous(ok = true))
        val mine = record(gen = 40)
        composite.publishIfNewer(mine)

        clock.advance(1_000) // the LAN slot ages out, but nobody superseded us

        assertTrue(composite.heartbeat(mine.withHeartbeat(1)))
    }

    @Test
    fun `heartbeat reports loss when the rendezvous itself supersedes`() = runBlocking {
        val lan = InMemoryWorldRegistry(FakeClock(0))
        val composite = CompositeWorldRegistry(lan, FakeRendezvous(ok = false))
        val mine = record(gen = 40)
        composite.publishIfNewer(mine)

        assertFalse(composite.heartbeat(mine.withHeartbeat(1)))
    }

    @Test
    fun `heartbeat stays alive for the sole live host`() = runBlocking {
        val lan = InMemoryWorldRegistry(FakeClock(0))
        val composite = CompositeWorldRegistry(lan, FakeRendezvous(ok = true))
        val mine = record(gen = 40)
        composite.publishIfNewer(mine)

        assertTrue(composite.heartbeat(mine.withHeartbeat(1)))
    }
}
