package dev.jukz.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WorldIdTest {

    @Test
    fun `short code round-trips the full uuid`() {
        repeat(2000) {
            val world = WorldId.random()
            val code = world.shortCode()
            assertTrue(code.startsWith("JUKZ-"), "missing prefix: $code")
            assertEquals(world, WorldId.fromShortCode(code), "round-trip failed for $code")
        }
    }

    @Test
    fun `short code is tolerant of case and a missing prefix`() {
        val world = WorldId(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"))
        val code = world.shortCode()
        assertEquals(world, WorldId.fromShortCode(code.lowercase()))
        assertEquals(world, WorldId.fromShortCode(code.removePrefix("JUKZ-")))
    }

    @Test
    fun `endpoint parse round-trips`() {
        val e = Endpoint("203.0.113.5", 25565)
        assertEquals(e, Endpoint.parse(e.format()))
        assertEquals(Endpoint("host.example", 1), Endpoint.parse("host.example:1"))
    }

    @Test
    fun `endpoint rejects bad input`() {
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("noport") }
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("h:notaport") }
        assertThrows(IllegalArgumentException::class.java) { Endpoint("h", 70000) }
    }
}
