package dev.jukz.core.model

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Permanent identity of a jukz world. Wraps a 128-bit UUID and renders a human-shareable
 * short code (Crockford Base32, grouped, `JUKZ-` prefixed) that round-trips the full UUID.
 */
@JvmInline
value class WorldId(val uuid: UUID) {

    /** A copyable share code, e.g. `JUKZ-AB12-CD34-...` encoding the full 128-bit id. */
    fun shortCode(): String {
        val bytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        return "JUKZ-" + Base32.encode(bytes).chunked(GROUP).joinToString("-")
    }

    override fun toString(): String = "WorldId(${uuid})"

    companion object {
        private const val GROUP = 4

        fun random(): WorldId = WorldId(UUID.randomUUID())

        fun of(uuid: UUID): WorldId = WorldId(uuid)

        /** Inverse of [shortCode]; tolerant of case, separators, and Crockford aliases. */
        fun fromShortCode(code: String): WorldId {
            val cleaned = code.trim().uppercase()
                .removePrefix("JUKZ-")
                .replace("-", "")
                .replace(" ", "")
            val bytes = Base32.decode(cleaned)
            require(bytes.size >= 16) { "short code decodes to ${bytes.size} bytes, need 16" }
            val bb = ByteBuffer.wrap(bytes, 0, 16)
            return WorldId(UUID(bb.long, bb.long))
        }
    }
}

/** Crockford Base32 (no padding). Excludes I, L, O, U; decode maps the common aliases. */
internal object Base32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val DECODE = IntArray(128) { -1 }.also { d ->
        ALPHABET.forEachIndexed { i, c -> d[c.code] = i }
        d['I'.code] = 1; d['L'.code] = 1; d['O'.code] = 0
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "invalid base32 char: '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
