package dev.jukz.core.guard

import java.security.MessageDigest

/**
 * The one-time puzzle that registers a new device key: find a nonce such that
 * sha256("<challenge>:<device key>:<nonce>") starts with [bits] zero bits. About a second on a PC at
 * the default 22 bits, done once per install in the background; mirrors workValid in
 * rendezvous-worker/src/guard-logic.ts.
 */
object ProofOfWork {
    fun solve(challenge: String, device: String, bits: Int, cancelled: () -> Boolean = { false }): Long? {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = "$challenge:$device:".toByteArray(Charsets.UTF_8)
        var nonce = 0L
        while (nonce < Long.MAX_VALUE) {
            if (nonce and 0xFFFF == 0L && cancelled()) return null
            digest.update(prefix)
            digest.update(nonce.toString().toByteArray(Charsets.US_ASCII))
            if (leadingZeroBits(digest.digest()) >= bits) return nonce
            nonce++
        }
        return null
    }

    fun check(challenge: String, device: String, nonce: Long, bits: Int): Boolean =
        leadingZeroBits(MessageDigest.getInstance("SHA-256").digest("$challenge:$device:$nonce".toByteArray(Charsets.UTF_8))) >= bits

    fun leadingZeroBits(bytes: ByteArray): Int {
        var bits = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += 8
                continue
            }
            return bits + Integer.numberOfLeadingZeros(v) - 24
        }
        return bits
    }
}
