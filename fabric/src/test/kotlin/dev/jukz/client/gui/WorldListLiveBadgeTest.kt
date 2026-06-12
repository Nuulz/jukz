package dev.jukz.client.gui

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WorldListLiveBadgeTest {

    private val worldId = WorldId.random()
    private val record = WorldRecord(worldId, ClaimToken(1, 1, NodeId.random()), Endpoint("127.0.0.1", 25565), 0)

    /** A registry that counts lookups, so the cache de-dup is observable. */
    private class CountingRegistry(private val record: WorldRecord?) : WorldRegistry {
        val lookups = AtomicInteger(0)
        override suspend fun publishIfNewer(record: WorldRecord) = PublishResult.Published(record)
        override suspend fun heartbeat(record: WorldRecord) = true
        override suspend fun lookup(worldId: WorldId): WorldRecord? {
            lookups.incrementAndGet()
            return record
        }
        override suspend fun withdraw(worldId: WorldId, token: ClaimToken) {}
    }

    @BeforeEach
    fun reset() = WorldListLiveBadge.clear()

    @Test
    fun `de-dups lookups within the 10s cache window`() {
        val registry = CountingRegistry(record)
        val clock = AtomicLong(1_000)
        val inline: (Runnable) -> Unit = { it.run() }

        val first = WorldListLiveBadge.ensureFresh(worldId, registry, { clock.get() }, inline)
        val second = WorldListLiveBadge.ensureFresh(worldId, registry, { clock.get() }, inline)

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, registry.lookups.get()) // exactly one registry call for two redraws
        assertEquals(record, WorldListLiveBadge.cachedRecord(worldId))
    }

    @Test
    fun `refreshes again once the cache window expires`() {
        val registry = CountingRegistry(record)
        val clock = AtomicLong(1_000)
        val inline: (Runnable) -> Unit = { it.run() }

        WorldListLiveBadge.ensureFresh(worldId, registry, { clock.get() }, inline)
        clock.set(1_000 + WorldListLiveBadge.CACHE_MS) // at the window edge -> stale
        val refreshed = WorldListLiveBadge.ensureFresh(worldId, registry, { clock.get() }, inline)

        assertTrue(refreshed)
        assertEquals(2, registry.lookups.get())
    }
}
