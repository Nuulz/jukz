package dev.jukz.discovery

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId

/**
 * Live discovery over the BitTorrent Mainline DHT via the8472/mldht (FLAGGED — requires the mldht
 * dependency from JitPack and live-network testing). Drops in behind [WorldRegistry] with no change
 * to the election core; [dev.jukz.core.discovery.InMemoryWorldRegistry] is used until enabled.
 *
 * Real wiring (BEP44 mutable items, key = Ed25519 derived from worldId + secret, salt = worldId):
 *  - publishIfNewer: dht.put(pubKey, privKey, salt, value=encode(record), seq=token.hostGeneration)
 *    with CAS on seq; map a higher existing seq to [PublishResult.Rejected].
 *  - lookup: dht.get(pubKey, salt) -> decode(value) (verify Ed25519 signature).
 *  - heartbeat: re-put the same seq with a bumped heartbeatSeq in the value, every ~10 min.
 *  - withdraw: rely on the ~2h BEP44 expiry; no explicit delete exists in the protocol.
 * Bootstrap: dht.transmissionbt.com:6881, dht.libtorrent.org:25401 + an on-disk node cache.
 */
class MldhtWorldRegistry : WorldRegistry {

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult =
        throw NotImplementedError("MldhtWorldRegistry requires the mldht dependency and live testing")

    override suspend fun heartbeat(record: WorldRecord): Boolean =
        throw NotImplementedError("MldhtWorldRegistry requires the mldht dependency and live testing")

    override suspend fun lookup(worldId: WorldId): WorldRecord? =
        throw NotImplementedError("MldhtWorldRegistry requires the mldht dependency and live testing")

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken) {
        throw NotImplementedError("MldhtWorldRegistry requires the mldht dependency and live testing")
    }
}
