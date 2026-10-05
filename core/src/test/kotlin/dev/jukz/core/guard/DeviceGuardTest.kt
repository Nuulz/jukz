package dev.jukz.core.guard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DeviceGuardTest {

    @Test
    fun `a device key round-trips through its file and signs what the rendezvous checks`() {
        val key = DeviceKey.generate()
        val again = DeviceKey.decode(key.encode())
        assertEquals(key.publicKeyText(), again.publicKeyText())

        val headers = again.headers("POST", "/v1/announce", """{"worldId":"x"}""", 1_800_000_000_000)
        val sig = Base64.getUrlDecoder().decode(headers.getValue(DeviceKey.HEADER_SIG))
        assertTrue(DeviceKey.verify(key.publicKey, "POST", "/v1/announce", 1_800_000_000_000, """{"worldId":"x"}""", sig))
        assertFalse(DeviceKey.verify(key.publicKey, "POST", "/v1/withdraw", 1_800_000_000_000, """{"worldId":"x"}""", sig))
    }

    @Test
    fun `the puzzle is solved and checked the same way`() {
        val nonce = ProofOfWork.solve("challenge", "device", 12)!!
        assertTrue(ProofOfWork.check("challenge", "device", nonce, 12))
        assertEquals(11, ProofOfWork.leadingZeroBits(byteArrayOf(0, 0x1f)))
        assertEquals(0, ProofOfWork.leadingZeroBits(byteArrayOf(0x80.toByte())))
    }
}
