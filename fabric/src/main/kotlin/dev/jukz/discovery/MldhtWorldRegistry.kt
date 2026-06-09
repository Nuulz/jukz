package dev.jukz.discovery

import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.WorldId

/**
 * Internet-scale discovery over the BitTorrent Mainline DHT via the8472/mldht, using BEP44 mutable
 * items (FLAGGED — needs the `mldht` + `net.i2p.crypto:eddsa` dependencies and live-network testing;
 * I could not validate a real DHT round-trip in this environment, so it is left as a precise,
 * reverse-engineered blueprint rather than an unvalidated blob). It drops in behind [WorldRegistry]
 * with no change to the rest of jukz; until enabled, [LanMulticastWorldRegistry] is the live backend
 * (real same-network discovery) and [dev.jukz.core.discovery.InMemoryWorldRegistry] the test fake.
 *
 * ## Exact mldht recipe (API verified against the published jar)
 *
 * Identity — derive a per-world Ed25519 keypair from the [WorldId] so any holder of the share code
 * can read/write the same DHT slot; the BEP44 sequence number is the fence:
 * ```
 * val seed   = sha512(worldId.uuid.bytes)            // 64 bytes -> Ed25519 private seed
 * val priv   = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed.copyOf(32), GenericStorage.StorageItem.spec))
 * val pubKey = priv.a.toByteArray()                  // 32-byte public key
 * val salt   = "jukz".toByteArray()
 * val target = GenericStorage.fingerprint(pubKey, salt, null)   // -> kad.Key (sha1(pubKey+salt))
 * ```
 * Node lifecycle (once per process, shared by all worlds):
 * ```
 * val dht = DHT(DHTtype.IPV4_DHT)
 * dht.start(config)                 // DHTConfiguration: getStoragePath()=node cache, getListeningPort()=0,
 *                                   //   noRouterBootstrap()=false, isPersistingID()=true, allowMultiHoming()=false
 * dht.bootstrap()                   // dht.transmissionbt.com:6881, dht.libtorrent.org:25401
 * // await dht.isRunning() && the routing table to settle (a few seconds) before the first op
 * val server = dht.serverManager.getRandomActiveServer(true)
 * val node   = dht.node
 * ```
 * publishIfNewer(record) — BEP44 put is two-phase (find closest nodes + write tokens, then store):
 * ```
 * val value = WorldRecordCodec.encode(record)                  // < 1000 bytes, our bencode-able value
 * val item  = GenericStorage.buildMutable(value, priv, salt, record.token.hostGeneration) // seq = generation
 * val lookup = GetLookupTask(target, server, node).apply { expectedSalt(salt); setSequence(-1) }
 * runTask(lookup)                                              // await completion
 * val put = PutTask(server, node, lookup.tokens, item)
 * runTask(put)                                                 // await completion
 * // CAS: a concurrent get returning a strictly higher seq (== a higher generation) => Rejected.
 * ```
 * lookup(worldId) — BEP44 get, newest (highest seq) wins:
 * ```
 * val newest = AtomicReference<StorageItem?>()
 * val get = GetLookupTask(target, server, node).apply {
 *     expectedSalt(salt)
 *     setValueConsumer { item -> if (item.validateSig()) keepIfNewerBySeq(newest, item) }
 * }
 * runTask(get)                                                 // await
 * return newest.get()?.let { WorldRecordCodec.decode(it.rawValue.array()) }
 * ```
 * heartbeat(record) — re-put the same seq (generation) with a bumped heartbeatSeq in the value, every
 * ~10 min (BEP44 items expire in ~2h); returns false if a get sees a higher seq (we were superseded).
 * withdraw — no BEP44 delete exists; stop re-putting and let the item expire (~2h).
 *
 * Tasks complete asynchronously: register a completion callback (the mldht `Task`/`TaskListener`
 * API) or block on a CompletableFuture the callback resolves — this async handling is the part that
 * most needs real-DHT validation, which is why it is documented rather than guessed at here.
 */
class MldhtWorldRegistry : WorldRegistry {

    private fun flagged(): Nothing = throw NotImplementedError(
        "MldhtWorldRegistry needs the mldht + net.i2p.crypto:eddsa dependencies and live-network " +
            "testing. The exact BEP44 recipe is documented in this file's KDoc; the LAN path " +
            "(LanMulticastWorldRegistry) is the working live backend until it is enabled.",
    )

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult = flagged()

    override suspend fun heartbeat(record: WorldRecord): Boolean = flagged()

    override suspend fun lookup(worldId: WorldId): WorldRecord? = flagged()

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken) = flagged()
}
