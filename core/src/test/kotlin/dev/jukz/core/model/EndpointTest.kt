package dev.jukz.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EndpointTest {
    @Test
    fun `IPv4 and names round-trip unchanged`() {
        assertEquals("10.0.0.2:25565", Endpoint("10.0.0.2", 25565).format())
        assertEquals(Endpoint("example.org", 80), Endpoint.parse("example.org:80"))
    }

    @Test
    fun `IPv6 is bracketed so the port stays unambiguous`() {
        val e = Endpoint("2001:db8::1", 25565)
        assertEquals("[2001:db8::1]:25565", e.format())
        assertEquals(e, Endpoint.parse(e.format()))
    }
}
