package dev.jukz.core.model

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

class WorldKeyTest {

    private val world = WorldId.of(UUID.fromString("3c4f5d44-f37f-475c-8017-f1c9a8a3701f"))
    private val other = WorldId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"))

    @Test
    fun `a signature verifies only for its own key, operation, world, time and body`() {
        val key = WorldKey.generate()
        val sig = key.sign(WorldKey.OP_ANNOUNCE, world, 1000, """{"a":1}""")
        assertTrue(WorldKey.verify(key.publicKey, WorldKey.OP_ANNOUNCE, world, 1000, """{"a":1}""", sig))
        assertFalse(WorldKey.verify(WorldKey.generate().publicKey, WorldKey.OP_ANNOUNCE, world, 1000, """{"a":1}""", sig))
        assertFalse(WorldKey.verify(key.publicKey, WorldKey.OP_WITHDRAW, world, 1000, """{"a":1}""", sig))
        assertFalse(WorldKey.verify(key.publicKey, WorldKey.OP_ANNOUNCE, other, 1000, """{"a":1}""", sig))
        assertFalse(WorldKey.verify(key.publicKey, WorldKey.OP_ANNOUNCE, world, 1001, """{"a":1}""", sig))
        assertFalse(WorldKey.verify(key.publicKey, WorldKey.OP_ANNOUNCE, world, 1000, """{"a":2}""", sig))
    }

    @Test
    fun `the key file round-trips and keeps signing with the same identity`() {
        val key = WorldKey.generate()
        val back = WorldKey.decode(key.encode())
        assertArrayEquals(key.publicKey, back.publicKey)
        assertEquals(32, back.publicKey.size)
        val sig = back.sign(WorldKey.OP_HEARTBEAT, world, 5, "x")
        assertTrue(WorldKey.verify(key.publicKey, WorldKey.OP_HEARTBEAT, world, 5, "x", sig))
    }

    @Test
    fun `a corrupt or mismatched key file is rejected`() {
        assertThrows(Exception::class.java) { WorldKey.decode("hello") }
        val a = WorldKey.generate().encode().lines()
        val b = WorldKey.generate().encode().lines()
        assertThrows(Exception::class.java) { WorldKey.decode("${a[0]}\n${a[1]}\n${b[2]}\n") }
    }

    @Test
    fun `headers carry the public key, timestamp and a base64url signature`() {
        val key = WorldKey.generate()
        val h = key.headers(WorldKey.OP_SNAPSHOT_UPLOAD, world, "{}", 42)
        assertEquals(key.publicKeyText(), h[WorldKey.HEADER_KEY])
        assertEquals("42", h[WorldKey.HEADER_TS])
        val sig = Base64.getUrlDecoder().decode(h[WorldKey.HEADER_SIG])
        assertTrue(WorldKey.verify(key.publicKey, WorldKey.OP_SNAPSHOT_UPLOAD, world, 42, "{}", sig))
    }
}
