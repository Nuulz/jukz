package dev.jukz.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaimTokenTest {

    private fun node(last: Int): NodeId = NodeId(ByteArray(16).also { it[15] = last.toByte() })

    @Test
    fun `higher generation wins regardless of millis and nodeId`() {
        val low = ClaimToken(hostGeneration = 5, claimEpochMillis = Long.MAX_VALUE, nodeId = node(0xFF))
        val high = ClaimToken(hostGeneration = 6, claimEpochMillis = 0, nodeId = node(0x00))
        assertTrue(high > low)
    }

    @Test
    fun `equal generation - higher millis wins`() {
        val a = ClaimToken(5, 100, node(0xFF))
        val b = ClaimToken(5, 200, node(0x00))
        assertTrue(b > a)
    }

    @Test
    fun `equal generation and millis - higher nodeId wins`() {
        val a = ClaimToken(5, 100, node(0x01))
        val b = ClaimToken(5, 100, node(0x02))
        assertTrue(b > a)
    }

    @Test
    fun `identical fields compare equal`() {
        val a = ClaimToken(5, 100, node(0x07))
        val b = ClaimToken(5, 100, node(0x07))
        assertEquals(0, a.compareTo(b))
        assertEquals(a, b)
    }
}
