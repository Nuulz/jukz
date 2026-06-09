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
 * announces over LAN multicast or stores as a BEP44 DHT item. Stays well under the BEP44 1000-byte
 * limit (~60 bytes). A leading magic + version makes a malformed or foreign datagram cheap to reject.
 */
object WorldRecordCodec {

    private const val MAGIC = 0x6A_6B_7A_31 // "jkz1"
    private const val VERSION = 1

    fun encode(record: WorldRecord): ByteArray {
        val bos = ByteArrayOutputStream(64)
        DataOutputStream(bos).use { o ->
            o.writeInt(MAGIC)
            o.writeByte(VERSION)
            o.writeLong(record.worldId.uuid.mostSignificantBits)
            o.writeLong(record.worldId.uuid.leastSignificantBits)
            o.writeLong(record.token.hostGeneration)
            o.writeLong(record.token.claimEpochMillis)
            o.write(record.token.nodeId.bytes) // exactly NodeId.SIZE bytes
            o.writeUTF(record.endpoint.host)
            o.writeInt(record.endpoint.port)
            o.writeLong(record.heartbeatSeq)
        }
        return bos.toByteArray()
    }

    /** Decode a [WorldRecord], or null if the bytes are not a valid jukz record (wrong magic/version). */
    fun decodeOrNull(bytes: ByteArray): WorldRecord? = runCatching { decode(bytes) }.getOrNull()

    fun decode(bytes: ByteArray): WorldRecord {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readInt() == MAGIC) { "not a jukz record (bad magic)" }
            require(i.readUnsignedByte() == VERSION) { "unsupported jukz record version" }
            val msb = i.readLong()
            val lsb = i.readLong()
            val generation = i.readLong()
            val claimEpochMillis = i.readLong()
            val nodeBytes = ByteArray(NodeId.SIZE).also { i.readFully(it) }
            val host = i.readUTF()
            val port = i.readInt()
            val heartbeatSeq = i.readLong()
            return WorldRecord(
                worldId = WorldId(UUID(msb, lsb)),
                token = ClaimToken(generation, claimEpochMillis, NodeId(nodeBytes)),
                endpoint = Endpoint(host, port),
                heartbeatSeq = heartbeatSeq,
            )
        }
    }
}
