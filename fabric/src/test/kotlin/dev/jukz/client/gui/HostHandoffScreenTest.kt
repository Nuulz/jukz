package dev.jukz.client.gui

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostHandoffScreenTest {

    @Test
    fun `applied-snapshot variant offers to host with the latest copy`() {
        val msg = HostHandoffScreen.message(snapshotApplied = true)
        assertTrue(msg.contains("last known copy"), msg)
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
