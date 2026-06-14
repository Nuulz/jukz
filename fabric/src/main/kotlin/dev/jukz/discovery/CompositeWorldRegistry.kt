package dev.jukz.discovery

import dev.jukz.JukzMod
import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId

/**
 * Composes the LAN registry (multicast, zero-latency, works with no internet) with the rendezvous
 * registry (internet-wide). Same-network play keeps working when the backend is down, and two
 * players on one LAN find each other directly even when both are also announced globally.
 *
 * Discrepancies are resolved the only way the protocol allows: by [ClaimToken] order. The
 * rendezvous server is authoritative for lease verdicts (supersession), because it is the one
 * store every host can reach; the LAN side is kept in sync best-effort.
 */
class CompositeWorldRegistry(
    private val lan: WorldRegistry,
    private val rendezvous: WorldRegistry,
) : WorldRegistry {

    override fun leaseTtlMs(): Long? = rendezvous.leaseTtlMs() ?: lan.leaseTtlMs()

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult {
        // Global truth first: a global rejection means a newer host exists somewhere.
        val global = rendezvous.publishIfNewer(record)
        if (global is PublishResult.Rejected) return global

        // Mirror the (server-enriched) record onto the LAN so same-network guests get it directly.
        val enriched = (global as PublishResult.Published).record
        val local = runCatching { lan.publishIfNewer(enriched) }
            .getOrElse { e ->
                JukzMod.logger.warn("jukz: LAN publish failed ({}); continuing with rendezvous only", e.message)
                return global
            }
        if (local is PublishResult.Rejected) {
            // A LAN peer holds a strictly newer token; it is (or will be) the global host too.
            runCatching { rendezvous.withdraw(record.worldId, record.token) }
            return local
        }
        return global
    }

    override suspend fun heartbeat(record: WorldRecord): Boolean {
        runCatching { lan.heartbeat(record) } // best-effort refresh; LAN re-announces on its own
        // The rendezvous is the authoritative lease verdict, BUT it is optimistically `true` when the
        // backend is unreachable — so on a LAN-only / rendezvous-down deployment a host that a newer
        // same-network peer has already superseded would otherwise keep this returning true forever and
        // never withdraw (the 2026-06-13 permanent split-brain). A definitive rendezvous "superseded"
        // (false) loses immediately; otherwise we still honour a genuine LAN supersession — a strictly
        // higher token holding the slot. A transient LAN cache miss (lookup null / our own token) is NOT
        // a supersession, so a healthy host is never self-evicted by a dropped multicast.
        if (!rendezvous.heartbeat(record)) return false
        val lanCurrent = runCatching { lan.lookup(record.worldId) }.getOrNull()
        if (lanCurrent != null && lanCurrent.token > record.token) return false
        return true
    }

    override suspend fun lookup(worldId: WorldId): WorldRecord? {
        val local = runCatching { lan.lookup(worldId) }.getOrNull()
        val global = runCatching { rendezvous.lookup(worldId) }.getOrNull()
        return when {
            local == null -> global
            global == null -> local
            global.token > local.token -> global
            else -> local // LAN first on a tie: its endpoint is directly dialable
        }
    }

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken) {
        runCatching { lan.withdraw(worldId, token) }
        runCatching { rendezvous.withdraw(worldId, token) }
    }
}
