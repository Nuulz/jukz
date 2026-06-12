package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WorldRecordCodecRelayTest {

    private fun record(relay: RelayOffer?) = WorldRecord(
        worldId = WorldId.random(),
        token = ClaimToken(7, 1_700_000_000_000, NodeId(ByteArray(NodeId.SIZE) { 1 })),
        endpoints = listOf(Endpoint("1.2.3.4", 25565)),
        heartbeatSeq = 3,
        relay = relay,
    )

    @Test
    fun `round-trips a record carrying a relay offer`() {
        val original = record(RelayOffer("cap-abc123"))
        assertEquals(original, WorldRecordCodec.decode(WorldRecordCodec.encode(original)))
    }

    @Test
    fun `round-trips a record with no relay offer`() {
        val original = record(null)
        val decoded = WorldRecordCodec.decode(WorldRecordCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded.relay)
    }

    @Test
    fun `decodes a v3 record as relay-null (back-compat)`() {
        // Hand-build a v3 buffer (version byte 3, no relay tail) and prove it still decodes.
        val node = ByteArray(NodeId.SIZE) { 2 }
        val world = WorldId.random()
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x6A_6B_7A_31) // MAGIC
            o.writeByte(3)            // VERSION 3
            o.writeLong(world.uuid.mostSignificantBits)
            o.writeLong(world.uuid.leastSignificantBits)
            o.writeLong(9)            // generation
            o.writeLong(1_700_000_000_001) // claimEpochMillis
            o.write(node)
            o.writeByte(1)            // endpoint count
            o.writeUTF("5.6.7.8"); o.writeInt(25566)
            o.writeLong(4)            // heartbeatSeq
            o.writeBoolean(false)     // no snapshot
            o.writeInt(2)             // playerCount
        }
        val decoded = WorldRecordCodec.decode(bos.toByteArray())
        assertNull(decoded.relay)
        assertEquals(2, decoded.playerCount)
        assertEquals(world, decoded.worldId)
    }
}
