package dev.jukz.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * Minimal RFC 5389 STUN client. Sends a Binding Request to a public STUN server and parses the
 * XOR-MAPPED-ADDRESS to learn this host's server-reflexive (public) ip:port. This is fully
 * functional with no external dependency, but is only one ingredient of the larger
 * ICE/hole-punch flow (IceTransport), which remains flagged for live-network testing.
 */
class StunClient(private val timeoutMs: Int = 3_000) {

    data class MappedAddress(val host: String, val port: Int)

    /** Discover the public mapping of an existing UDP [socket] (so the same socket can hole-punch). */
    fun discover(socket: DatagramSocket, stunHost: String = DEFAULT_HOST, stunPort: Int = DEFAULT_PORT): MappedAddress? {
        val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val request = ByteArray(20).apply {
            this[0] = 0x00; this[1] = 0x01            // Binding Request
            this[2] = 0x00; this[3] = 0x00            // length 0
            this[4] = 0x21; this[5] = 0x12            // magic cookie 0x2112A442
            this[6] = 0xA4.toByte(); this[7] = 0x42
            System.arraycopy(txId, 0, this, 8, 12)
        }
        val server = InetSocketAddress(InetAddress.getByName(stunHost), stunPort)
        val previousTimeout = socket.soTimeout
        return try {
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(request, request.size, server))
            val buf = ByteArray(512)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            parse(buf, response.length)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.soTimeout = previousTimeout }
        }
    }

    /** Convenience: discover on a fresh ephemeral socket. */
    fun discover(stunHost: String = DEFAULT_HOST, stunPort: Int = DEFAULT_PORT): MappedAddress? =
        DatagramSocket().use { discover(it, stunHost, stunPort) }

    private fun parse(data: ByteArray, length: Int): MappedAddress? {
        if (length < 20) return null
        var pos = 20 // skip the 20-byte STUN header
        while (pos + 4 <= length) {
            val attrType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val attrLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            val valueStart = pos + 4
            if (valueStart + attrLen > length) break
            // 0x0020 = XOR-MAPPED-ADDRESS, 0x0001 = MAPPED-ADDRESS
            if (attrType == 0x0020 || attrType == 0x0001) {
                val xor = attrType == 0x0020
                val family = data[valueStart + 1].toInt() and 0xFF
                if (family == 0x01) { // IPv4
                    var port = ((data[valueStart + 2].toInt() and 0xFF) shl 8) or (data[valueStart + 3].toInt() and 0xFF)
                    val addr = ByteArray(4) { data[valueStart + 4 + it] }
                    if (xor) {
                        port = port xor 0x2112
                        val magic = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                        for (i in 0 until 4) addr[i] = (addr[i].toInt() xor magic[i].toInt()).toByte()
                    }
                    val host = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    return MappedAddress(host, port)
                }
            }
            // attributes are 32-bit aligned
            pos = valueStart + attrLen + ((4 - (attrLen % 4)) % 4)
        }
        return null
    }

    companion object {
        const val DEFAULT_HOST = "stun.l.google.com"
        const val DEFAULT_PORT = 19302
    }
}
