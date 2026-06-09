package dev.jukz.core.model

import java.security.SecureRandom

/**
 * 16-byte per-install identity. NOT the player UUID. Used as the final, deterministic
 * tiebreak in [ClaimToken] via unsigned lexicographic comparison, so two distinct
 * installs can never produce a true tie.
 */
class NodeId(val bytes: ByteArray) : Comparable<NodeId> {
    init {
        require(bytes.size == SIZE) { "NodeId must be $SIZE bytes, was ${bytes.size}" }
    }

    override fun compareTo(other: NodeId): Int {
        for (i in 0 until SIZE) {
            val a = bytes[i].toInt() and 0xFF
            val b = other.bytes[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is NodeId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    override fun toString(): String = "NodeId(${toHex()})"

    companion object {
        const val SIZE = 16
        private val RNG = SecureRandom()

        fun random(): NodeId {
            val b = ByteArray(SIZE)
            RNG.nextBytes(b)
            return NodeId(b)
        }

        fun fromHex(hex: String): NodeId {
            require(hex.length == SIZE * 2) { "expected ${SIZE * 2} hex chars, got ${hex.length}" }
            val b = ByteArray(SIZE) {
                ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte()
            }
            return NodeId(b)
        }
    }
}
