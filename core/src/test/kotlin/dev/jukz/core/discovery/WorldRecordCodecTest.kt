package dev.jukz.core.discovery

import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldRecordCodecTest {

    private val record = WorldRecord(
        worldId = WorldId.random(),
        token = ClaimToken(7, 1_700_000_000_000, NodeId.random()),
        endpoint = Endpoint("203.0.113.9", 51820),
        heartbeatSeq = 42,
    )

    @Test
    fun `round-trips a record byte-for-byte`() {
        val decoded = WorldRecordCodec.decode(WorldRecordCodec.encode(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `stays well under the BEP44 1000-byte limit`() {
        assertTrue(WorldRecordCodec.encode(record).size < 1000)
    }

    @Test
    fun `rejects foreign or truncated bytes`() {
        assertNull(WorldRecordCodec.decodeOrNull("not a jukz datagram".toByteArray()))
        assertNull(WorldRecordCodec.decodeOrNull(WorldRecordCodec.encode(record).copyOf(10)))
        assertNull(WorldRecordCodec.decodeOrNull(ByteArray(0)))
    }
}
