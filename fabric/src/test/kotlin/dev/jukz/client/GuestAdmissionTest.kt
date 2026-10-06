package dev.jukz.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuestAdmissionTest {

    @Test
    fun `guests without an account are allowed only when the host has none or opted in`() {
        assertFalse(GuestAdmission.allowUnverified(hostHasPremiumAccount = true, offlineGuestsOptIn = false))
        assertTrue(GuestAdmission.allowUnverified(hostHasPremiumAccount = true, offlineGuestsOptIn = true))
        assertTrue(GuestAdmission.allowUnverified(hostHasPremiumAccount = false, offlineGuestsOptIn = false))
    }

    @Test
    fun `a verified guest is left to vanilla, even under a name already in the world`() {
        assertNull(GuestAdmission.refusal(true, false, false, "HostA", "HostA", listOf("HostA")))
    }

    @Test
    fun `an unverified guest is refused when the host doesn't allow them`() {
        assertEquals(GuestAdmission.ACCOUNT_REQUIRED, GuestAdmission.refusal(false, false, false, "GuestC", "HostA", emptyList()))
    }

    @Test
    fun `an unverified guest can't take the host's name, in any case`() {
        assertEquals(GuestAdmission.NAME_TAKEN, GuestAdmission.refusal(false, true, false, "hosta", "HostA", emptyList()))
    }

    @Test
    fun `an unverified guest can't take a name already in the world, so nobody gets kicked`() {
        assertEquals(GuestAdmission.NAME_TAKEN, GuestAdmission.refusal(false, true, false, "HostA", "GuestB", listOf("GuestB", "HostA")))
    }

    @Test
    fun `an unverified guest with a new name gets in, and the host's own connection is never refused`() {
        assertNull(GuestAdmission.refusal(false, true, false, "GuestC", "HostA", listOf("HostA")))
        assertNull(GuestAdmission.refusal(false, false, true, "HostA", "HostA", listOf("HostA")))
    }

    @Test
    fun `the host hears of a refused guest once a minute, not on every retry`() {
        val heard = mutableListOf<String>()
        GuestAdmission.onRefusedUnverified = { heard += it }
        GuestAdmission.refusedUnverified("Retry", now = 1_000)
        GuestAdmission.refusedUnverified("retry", now = 30_000)
        GuestAdmission.refusedUnverified("Retry", now = 61_001)
        assertEquals(listOf("Retry", "Retry"), heard)
        GuestAdmission.onRefusedUnverified = {}
    }
}
