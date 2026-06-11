package dev.jukz.core.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RelayOfferTest {

    @Test
    fun `keeps the session id`() {
        assertEquals("s3ss10n", RelayOffer("s3ss10n").sessionId)
    }

    @Test
    fun `rejects a blank session id`() {
        assertThrows(IllegalArgumentException::class.java) { RelayOffer(" ") }
    }
}
