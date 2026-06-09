package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.JukzClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [WorldRegistry] that faithfully models the two DHT properties the protocol
 * relies on: CAS-on-token publishing and time-based record expiry (BEP44's ~2h TTL).
 * Time is driven by an injected [JukzClock], so tests can expire records deterministically.
 *
 * This is the test/dev backend; production swaps in a Mainline-DHT adapter behind the same
 * interface. It is concurrency-safe so it can also stand in for local multi-peer simulations.
 */
class InMemoryWorldRegistry(
    private val clock: JukzClock,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : WorldRegistry {

    private data class Entry(val record: WorldRecord, val publishedAt: Long)

    private val map = HashMap<WorldId, Entry>()
    private val mutex = Mutex()

    /** Returns the entry if present and not expired; lazily evicts expired entries. */
    private fun liveEntry(worldId: WorldId): Entry? {
        val e = map[worldId] ?: return null
        if (clock.nowMillis() - e.publishedAt >= ttlMs) {
            map.remove(worldId)
            return null
        }
        return e
    }

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult = mutex.withLock {
        val current = liveEntry(record.worldId)
        if (current != null && current.record.token >= record.token) {
            PublishResult.Rejected(current.record)
        } else {
            map[record.worldId] = Entry(record, clock.nowMillis())
            PublishResult.Published(record)
        }
    }

    override suspend fun heartbeat(record: WorldRecord): Boolean = mutex.withLock {
        val current = liveEntry(record.worldId)
        if (current != null && current.record.token == record.token) {
            map[record.worldId] = Entry(record, clock.nowMillis())
            true
        } else {
            false
        }
    }

    override suspend fun lookup(worldId: WorldId): WorldRecord? = mutex.withLock {
        liveEntry(worldId)?.record
    }

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken): Unit = mutex.withLock {
        val current = map[worldId]
        if (current != null && current.record.token == token) {
            map.remove(worldId)
        }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 2 * 60 * 60 * 1000L // ~BEP44 2h item expiry
    }
}
