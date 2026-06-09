package dev.jukz.core.util

import java.io.ByteArrayOutputStream

/** Thrown when a [ByteReader] runs past the end of the buffer or reads invalid data. */
class MalformedDataException(message: String) : Exception(message)

/** Minimal big-endian binary writer used by the handshake codec. */
class ByteWriter {
    private val out = ByteArrayOutputStream()

    fun putByte(b: Int): ByteWriter {
        out.write(b and 0xFF)
        return this
    }

    fun putBool(v: Boolean): ByteWriter = putByte(if (v) 1 else 0)

    fun putInt(v: Int): ByteWriter {
        for (i in 3 downTo 0) out.write((v ushr (i * 8)) and 0xFF)
        return this
    }

    fun putLong(v: Long): ByteWriter {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
        return this
    }

    /** Writes raw bytes with no length prefix. */
    fun putRaw(b: ByteArray): ByteWriter {
        out.write(b)
        return this
    }

    /** Writes a 4-byte length prefix followed by the bytes. */
    fun putBytes(b: ByteArray): ByteWriter {
        putInt(b.size)
        out.write(b)
        return this
    }

    fun putString(s: String): ByteWriter = putBytes(s.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Minimal big-endian binary reader; throws [MalformedDataException] on underflow. */
class ByteReader(private val data: ByteArray) {
    private var pos = 0
    val remaining: Int get() = data.size - pos

    private fun require(n: Int) {
        if (n < 0 || remaining < n) throw MalformedDataException("need $n bytes, have $remaining")
    }

    fun getByte(): Int {
        require(1)
        return data[pos++].toInt() and 0xFF
    }

    fun getBool(): Boolean = getByte() != 0

    fun getInt(): Int {
        require(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (data[pos++].toInt() and 0xFF) }
        return v
    }

    fun getLong(): Long {
        require(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
        return v
    }

    fun getRaw(n: Int): ByteArray {
        require(n)
        val b = data.copyOfRange(pos, pos + n)
        pos += n
        return b
    }

    fun getBytes(): ByteArray {
        val n = getInt()
        return getRaw(n)
    }

    fun getString(): String = String(getBytes(), Charsets.UTF_8)

    fun expectEnd() {
        if (remaining != 0) throw MalformedDataException("$remaining trailing bytes")
    }
}
