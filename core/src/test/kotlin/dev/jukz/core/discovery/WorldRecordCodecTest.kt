package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WorldRecordCodecTest {

    private val worldId = WorldId.random()
    private val nodeId = NodeId.random()
    private val token = ClaimToken(7, 1_700_000_000_000, nodeId)

    private val record = WorldRecord(
        worldId = worldId,
        token = token,
        endpoints = listOf(Endpoint("203.0.113.9", 51820), Endpoint("192.168.1.7", 51820)),
        heartbeatSeq = 42,
    )

    @Test
    fun `round-trips a multi-endpoint record byte-for-byte`() {
        val decoded = WorldRecordCodec.decode(WorldRecordCodec.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `round-trips a single-endpoint record`() {
        val single = record.copy(endpoints = listOf(Endpoint("203.0.113.9", 51820)))
        assertEquals(single, WorldRecordCodec.decode(WorldRecordCodec.encode(single)))
    }

    @Test
    fun `stays well under the 1000-byte datagram limit`() {
        assertTrue(WorldRecordCodec.encode(record).size < 1000)
    }

    @Test
    fun `round-trips a snapshot offer and player count`() {
        val withExtras = record.copy(
            snapshot = SnapshotOffer("192.168.1.7", 53777, "ab".repeat(32)),
            playerCount = 4,
        )
        assertEquals(withExtras, WorldRecordCodec.decode(WorldRecordCodec.encode(withExtras)))
    }

    @Test
    fun `decodes a version-2 record with snapshot and player count defaulted`() {
        // A v2 datagram (multi-endpoint, no v3 tail) as older peers still emit it.
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x6A_6B_7A_31) // "jkz1"
            o.writeByte(2)
            o.writeLong(worldId.uuid.mostSignificantBits)
            o.writeLong(worldId.uuid.leastSignificantBits)
            o.writeLong(token.hostGeneration)
            o.writeLong(token.claimEpochMillis)
            o.write(nodeId.bytes)
            o.writeByte(1) // endpoint count
            o.writeUTF("203.0.113.9")
            o.writeInt(51820)
            o.writeLong(42)
        }

        val decoded = WorldRecordCodec.decode(bos.toByteArray())

        assertEquals(record.copy(endpoints = listOf(Endpoint("203.0.113.9", 51820))), decoded)
        assertNull(decoded.snapshot)
        assertEquals(0, decoded.playerCount)
    }

    @Test
    fun `rejects foreign or truncated bytes`() {
        assertNull(WorldRecordCodec.decodeOrNull("not a jukz datagram".toByteArray()))
        assertNull(WorldRecordCodec.decodeOrNull(WorldRecordCodec.encode(record).copyOf(10)))
        assertNull(WorldRecordCodec.decodeOrNull(ByteArray(0)))
    }

    @Test
    fun `decodes a version-1 single-endpoint record`() {
        // A v1 datagram as older peers still emit it: one endpoint, no count prefix.
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x6A_6B_7A_31) // "jkz1"
            o.writeByte(1)
            o.writeLong(worldId.uuid.mostSignificantBits)
            o.writeLong(worldId.uuid.leastSignificantBits)
            o.writeLong(token.hostGeneration)
            o.writeLong(token.claimEpochMillis)
            o.write(nodeId.bytes)
            o.writeUTF("203.0.113.9")
            o.writeInt(51820)
            o.writeLong(42)
        }

        val decoded = WorldRecordCodec.decode(bos.toByteArray())

        assertEquals(record.copy(endpoints = listOf(Endpoint("203.0.113.9", 51820))), decoded)
    }
}
