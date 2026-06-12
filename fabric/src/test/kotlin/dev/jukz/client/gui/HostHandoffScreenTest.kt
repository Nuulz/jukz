package dev.jukz.client.gui

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostHandoffScreenTest {

    @Test
    fun `applied-snapshot variant offers to host the latest world for everyone`() {
        val msg = HostHandoffScreen.message(snapshotApplied = true)
        assertTrue(msg.contains("latest world"), msg)
        assertTrue(msg.contains("everyone"), msg) // general: there may be more than two players
        assertTrue(msg.contains("host now", ignoreCase = true), msg)
    }

    @Test
    fun `unavailable-snapshot variant continues from the local copy`() {
        val msg = HostHandoffScreen.message(snapshotApplied = false)
        assertTrue(msg.contains("local copy"), msg)
    }

    @Test
    fun `the two message variants differ`() {
        assertNotEquals(HostHandoffScreen.message(true), HostHandoffScreen.message(false))
    }

    @Test
    fun `constructs with both variants`() {
        assertDoesNotThrow { HostHandoffScreen(snapshotApplied = true, onHostNow = {}, onBack = {}) }
        assertDoesNotThrow { HostHandoffScreen(snapshotApplied = false, onHostNow = {}, onBack = {}) }
    }
}
