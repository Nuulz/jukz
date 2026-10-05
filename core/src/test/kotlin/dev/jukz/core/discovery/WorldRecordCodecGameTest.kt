package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.GameVersion
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WorldRecordCodecGameTest {

    private val worldId = WorldId.random()
    private val token = ClaimToken(7, 1_700_000_000_000, NodeId(ByteArray(NodeId.SIZE) { 1 }))

    private fun record(game: GameVersion?) = WorldRecord(
        worldId = worldId,
        token = token,
        endpoints = listOf(Endpoint("1.2.3.4", 25565)),
        heartbeatSeq = 3,
        relay = RelayOffer("cap"),
        game = game,
    )

    @Test
    fun `round-trips the host's Minecraft version`() {
        val original = record(GameVersion("1.21.11", 4671))
        assertEquals(original, WorldRecordCodec.decode(WorldRecordCodec.encode(original)))
    }

    @Test
    fun `round-trips a record without a version`() {
        val original = record(null)
        assertEquals(original, WorldRecordCodec.decode(WorldRecordCodec.encode(original)))
    }

    @Test
    fun `decodes a version-4 record from an older host with no version`() {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeInt(0x6A_6B_7A_31) // "jkz1"
            o.writeByte(4)
            o.writeLong(worldId.uuid.mostSignificantBits)
            o.writeLong(worldId.uuid.leastSignificantBits)
            o.writeLong(token.hostGeneration)
            o.writeLong(token.claimEpochMillis)
            o.write(token.nodeId.bytes)
            o.writeByte(1)
            o.writeUTF("1.2.3.4")
            o.writeInt(25565)
            o.writeLong(3)
            o.writeBoolean(false) // no snapshot offer
            o.writeInt(0)
            o.writeBoolean(true) // relay offer
            o.writeUTF("cap")
        }
        val decoded = WorldRecordCodec.decode(bos.toByteArray())
        assertEquals(record(null), decoded)
        assertNull(decoded.game)
    }
}
