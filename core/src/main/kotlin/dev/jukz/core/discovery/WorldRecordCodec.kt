package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Compact, self-describing binary encoding of a [WorldRecord] for the wire — the value a host
 * announces over LAN multicast (and the shape the rendezvous adapter mirrors). Stays compact
 * (~60 bytes). A leading magic + version makes a malformed or foreign datagram cheap to reject.
 */
object WorldRecordCodec {

    private const val MAGIC = 0x6A_6B_7A_31 // "jkz1"
    private const val VERSION = 4            // current: + optional relay offer
    private const val V3_SNAPSHOT = 3        // optional snapshot offer + player count
    private const val V2_MULTI_ENDPOINT = 2  // multi-endpoint candidate list, no snapshot
    private const val LEGACY_VERSION = 1     // single endpoint, no count prefix

    /** Decode guard: a record never carries more candidates than this. */
    const val MAX_ENDPOINTS = 8

    fun encode(record: WorldRecord): ByteArray {
        require(record.endpoints.size <= MAX_ENDPOINTS) {
            "too many endpoints: ${record.endpoints.size} > $MAX_ENDPOINTS"
        }
        val bos = ByteArrayOutputStream(64)
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC)
            o.writeByte(VERSION)
            o.writeLong(record.worldId.uuid.mostSignificantBits)
            o.writeLong(record.worldId.uuid.leastSignificantBits)
            o.writeLong(record.token.hostGeneration)
            o.writeLong(record.token.claimEpochMillis)
            o.write(record.token.nodeId.bytes) // exactly NodeId.SIZE bytes
            o.writeByte(record.endpoints.size)
            for (endpoint in record.endpoints) {
                o.writeUTF(endpoint.host)
                o.writeInt(endpoint.port)
            }
            o.writeLong(record.heartbeatSeq)
            // v3 tail: an optional snapshot offer, then the connected player count.
            val snapshot = record.snapshot
            o.writeBoolean(snapshot != null)
            if (snapshot != null) {
                o.writeUTF(snapshot.host)
                o.writeInt(snapshot.port)
                o.writeUTF(snapshot.token)
            }
            o.writeInt(record.playerCount)
            // v4 tail: an optional relay session offer.
            val relay = record.relay
            o.writeBoolean(relay != null)
            if (relay != null) {
                o.writeUTF(relay.sessionId)
            }
        }
        return bos.toByteArray()
    }

    /** Decode a [WorldRecord], or null if the bytes are not a valid jukz record (wrong magic/version). */
    fun decodeOrNull(bytes: ByteArray): WorldRecord? = runCatching { decode(bytes) }.getOrNull()

    fun decode(bytes: ByteArray): WorldRecord {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == MAGIC) { "not a jukz record (bad magic)" }
            val version = i.readUnsignedByte()
            require(version in LEGACY_VERSION..VERSION) { "unsupported jukz record version" }
            val msb = i.readLong()
            val lsb = i.readLong()
            val generation = i.readLong()
            val claimEpochMillis = i.readLong()
            val nodeBytes = ByteArray(NodeId.SIZE).also { i.readFully(it) }
            val count = if (version == LEGACY_VERSION) 1 else i.readUnsignedByte()
            require(count in 1..MAX_ENDPOINTS) { "endpoint count out of range: $count" }
            val endpoints = List(count) {
                val host = i.readUTF()
                val port = i.readInt()
                Endpoint(host, port)
            }
            val heartbeatSeq = i.readLong()
            // v3 tail (absent in v1/v2: snapshot=null, playerCount=0).
            var snapshot: SnapshotOffer? = null
            var playerCount = 0
            var relay: RelayOffer? = null
            if (version > V2_MULTI_ENDPOINT) {
                if (i.readBoolean()) {
                    snapshot = SnapshotOffer(i.readUTF(), i.readInt(), i.readUTF())
                }
                playerCount = i.readInt()
            }
            // v4 tail (absent in v1/v2/v3: relay=null).
            if (version > V3_SNAPSHOT) {
                if (i.readBoolean()) {
                    relay = RelayOffer(i.readUTF())
                }
            }
            return WorldRecord(
                worldId = WorldId(UUID(msb, lsb)),
                token = ClaimToken(generation, claimEpochMillis, NodeId(nodeBytes)),
                endpoints = endpoints,
                heartbeatSeq = heartbeatSeq,
                snapshot = snapshot,
                playerCount = playerCount,
                relay = relay,
            )
        }
    }
}
