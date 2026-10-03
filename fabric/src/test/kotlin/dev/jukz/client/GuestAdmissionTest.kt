package dev.jukz.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestAdmissionTest {

    @Test
    fun `a host with a real account keeps Mojang verification unless they opt out`() {
        assertTrue(GuestAdmission.onlineMode(hostHasPremiumAccount = true, offlineGuestsOptIn = false))
        assertFalse(GuestAdmission.onlineMode(hostHasPremiumAccount = true, offlineGuestsOptIn = true))
    }

    @Test
    fun `a host without a real account (dev runs, offline launchers) can only host offline`() {
        assertFalse(GuestAdmission.onlineMode(hostHasPremiumAccount = false, offlineGuestsOptIn = false))
    }

    @Test
    fun `online mode leaves every login to vanilla`() {
        assertNull(GuestAdmission.refusal(true, false, "HostA", "HostA", listOf("HostA")))
    }

    @Test
    fun `offline mode refuses the host's name, in any case`() {
        assertEquals(GuestAdmission.NAME_TAKEN, GuestAdmission.refusal(false, false, "hosta", "HostA", emptyList()))
    }

    @Test
    fun `offline mode refuses a name already in the world, so nobody gets kicked`() {
        assertEquals(GuestAdmission.NAME_TAKEN, GuestAdmission.refusal(false, false, "HostA", "GuestB", listOf("GuestB", "HostA")))
    }

    @Test
    fun `offline mode lets a new name in, and never refuses the host's own connection`() {
        assertNull(GuestAdmission.refusal(false, false, "GuestC", "HostA", listOf("HostA")))
        assertNull(GuestAdmission.refusal(false, true, "HostA", "HostA", listOf("HostA")))
    }
}
