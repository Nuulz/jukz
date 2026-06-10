package dev.jukz.discovery

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRecordCodec
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.JukzClock
import dev.jukz.core.util.SystemClock
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * Real cross-machine discovery over **LAN multicast** — no server, no NAT. Every host periodically
 * multicasts its [WorldRecord] to a private group; every node listens and caches what it hears with
 * the same token-CAS + TTL semantics as [dev.jukz.core.discovery.InMemoryWorldRegistry], so two
 * Minecraft instances on the same network actually find and join each other's worlds. The rendezvous
 * adapter (`RendezvousWorldRegistry`) is the internet-scale counterpart behind the same
 * [WorldRegistry] interface; this is the same-subnet path that works with no internet at all.
 *
 * Fencing rides on the announced [ClaimToken]: a strictly-higher token in a received datagram takes
 * over the cache slot; equal tokens refresh the TTL (a host's re-announce); lower tokens are ignored.
 * A host that stops announcing (withdrew or crashed) ages out of every cache within [cacheTtlMs].
 */
class LanMulticastWorldRegistry(
    private val clock: JukzClock = SystemClock,
    groupAddress: String = DEFAULT_GROUP,
    private val port: Int = DEFAULT_PORT,
    private val announceIntervalMs: Long = 2_000,
    private val cacheTtlMs: Long = 6_000,
) : WorldRegistry, AutoCloseable {

    private data class Cached(val record: WorldRecord, val receivedAt: Long)

    private val group: InetAddress = InetAddress.getByName(groupAddress)
    private val socket = MulticastSocket(port)
    private val lock = Any()
    private val cache = HashMap<WorldId, Cached>()
    private val owned = HashMap<WorldId, WorldRecord>()
    @Volatile private var running = true

    init {
        socket.reuseAddress = true
        socket.timeToLive = 1 // stay on the local subnet
        runCatching { socket.joinGroup(group) }
        Thread(::listenLoop, "jukz-lan-listen").apply { isDaemon = true }.start()
        Thread(::announceLoop, "jukz-lan-announce").apply { isDaemon = true }.start()
    }

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult = synchronized(lock) {
        val current = liveCached(record.worldId)
        if (current != null && current.token >= record.token) {
            PublishResult.Rejected(current)
        } else {
            owned[record.worldId] = record
            cache[record.worldId] = Cached(record, clock.nowMillis())
            send(record)
            PublishResult.Published(record)
        }
    }

    override suspend fun heartbeat(record: WorldRecord): Boolean = synchronized(lock) {
        val current = liveCached(record.worldId)
        if (current != null && current.token == record.token) {
            owned[record.worldId] = record
            cache[record.worldId] = Cached(record, clock.nowMillis())
            send(record)
            true
        } else {
            false
        }
    }

    override suspend fun lookup(worldId: WorldId): WorldRecord? = synchronized(lock) { liveCached(worldId) }

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken): Unit = synchronized(lock) {
        owned.remove(worldId)
        val current = cache[worldId]
        if (current != null && current.record.token == token) cache.remove(worldId)
    }

    /** Cached record if present and not expired; lazily evicts stale entries. Caller holds [lock]. */
    private fun liveCached(worldId: WorldId): WorldRecord? {
        val entry = cache[worldId] ?: return null
        if (clock.nowMillis() - entry.receivedAt >= cacheTtlMs) {
            cache.remove(worldId)
            return null
        }
        return entry.record
    }

    private fun send(record: WorldRecord) {
        val bytes = WorldRecordCodec.encode(record)
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, port)) }
    }

    private fun listenLoop() {
        val buf = ByteArray(1024)
        while (running) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                if (running) continue else break
            }
            val record = WorldRecordCodec.decodeOrNull(packet.data.copyOf(packet.length)) ?: continue
            synchronized(lock) {
                val current = liveCached(record.worldId)
                // Equal-or-higher token wins: a re-announce refreshes TTL, a new host takes over.
                if (current == null || record.token >= current.token) {
                    cache[record.worldId] = Cached(record, clock.nowMillis())
                }
            }
        }
    }

    private fun announceLoop() {
        while (running) {
            try {
                Thread.sleep(announceIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
            val records = synchronized(lock) { owned.values.toList() }
            records.forEach { send(it) }
        }
    }

    override fun close() {
        running = false
        runCatching { socket.leaveGroup(group) }
        runCatching { socket.close() }
    }

    companion object {
        const val DEFAULT_GROUP = "239.255.41.78" // private (admin-scoped) multicast range
        const val DEFAULT_PORT = 54330
    }
}
