package dev.jukz.sync

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Minecraft region files (`.mca`, Anvil) store each chunk compressed on its own, so a world as a whole
 * barely compresses any further. Snapshots therefore keep regions "open": [open] rewrites a region with
 * every chunk decompressed (format JKZR1, deterministic), the snapshot is compressed as one stream (see
 * [SnapshotCodec]), and [close] turns it back into a normal region file Minecraft reads as usual.
 *
 * Nothing is dropped: every chunk, timestamp and the original compression of chunks we leave as they are
 * (external `.mcc` chunks, LZ4, unknown types) round-trips. A file that isn't a well-formed region is
 * left unchanged ([open] returns null), and [close] passes through anything that isn't JKZR1 — so
 * snapshots made before this format still apply.
 */
object RegionCodec {
    private val MAGIC = "JKZR1\n".toByteArray(Charsets.US_ASCII)
    private const val SECTOR = 4096
    private const val SLOTS = 1024

    /** Leave a file alone if any chunk is this big: re-encoding could need more sectors than fit (255). */
    private const val MAX_SECTORS_TO_OPEN = 200

    private const val GZIP: Byte = 1
    private const val ZLIB: Byte = 2

    fun isOpen(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && (MAGIC.indices).all { bytes[it] == MAGIC[it] }

    /** The JKZR1 form of a region file, or null to keep it as it is (empty, odd or already open). */
    fun open(region: ByteArray): ByteArray? {
        if (region.size < 2 * SECTOR || isOpen(region)) return null
        return runCatching { openOrNull(region) }.getOrNull() // anything odd: keep the file as it is
    }

    private fun openOrNull(region: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(region)
        val out = ByteArrayOutputStream(region.size * 4)
        val data = DataOutputStream(out)
        data.write(MAGIC)
        for (slot in 0 until SLOTS) {
            val location = buf.getInt(slot * 4)
            val sectorOffset = location ushr 8
            val sectorCount = location and 0xFF
            if (sectorOffset == 0 || sectorCount == 0) {
                data.writeByte(0)
                continue
            }
            if (sectorCount > MAX_SECTORS_TO_OPEN) return null
            val startLong = sectorOffset.toLong() * SECTOR
            if (sectorOffset < 2 || startLong + 5 > region.size) return null
            val start = startLong.toInt()
            val length = buf.getInt(start)
            if (length < 1 || start.toLong() + 4 + length > region.size) return null
            val type = region[start + 4]
            val payload = region.copyOfRange(start + 5, start + 4 + length)
            val raw = runCatching {
                when (type) {
                    ZLIB -> InflaterInputStream(payload.inputStream()).use { it.readBytes() }
                    GZIP -> GZIPInputStream(payload.inputStream()).use { it.readBytes() }
                    else -> null // external (.mcc), uncompressed, LZ4, unknown: kept byte for byte
                }
            }.getOrElse { return null }
            data.writeByte(1)
            data.writeInt(buf.getInt(SECTOR + slot * 4)) // timestamp
            if (raw != null) {
                data.writeByte(0) // opened: re-encoded with zlib on close
                data.writeInt(raw.size)
                data.write(raw)
            } else {
                data.writeByte(1) // as-is
                data.writeByte(type.toInt())
                data.writeInt(payload.size)
                data.write(payload)
            }
        }
        data.flush()
        return out.toByteArray()
    }

    /** A normal region file from [bytes] if they are JKZR1; anything else is returned unchanged. */
    fun close(bytes: ByteArray): ByteArray {
        if (!isOpen(bytes)) return bytes
        val input = DataInputStream(bytes.inputStream(MAGIC.size, bytes.size - MAGIC.size))
        val header = ByteBuffer.allocate(2 * SECTOR)
        val body = ByteArrayOutputStream()
        var nextSector = 2
        for (slot in 0 until SLOTS) {
            if (input.readByte().toInt() == 0) continue
            val timestamp = input.readInt()
            val entry = ByteArrayOutputStream()
            val entryData = DataOutputStream(entry)
            if (input.readByte().toInt() == 0) {
                val raw = ByteArray(input.readInt()).also { input.readFully(it) }
                val compressed = ByteArrayOutputStream(raw.size / 4 + 64)
                DeflaterOutputStream(compressed, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(raw) }
                entryData.writeInt(compressed.size() + 1)
                entryData.writeByte(ZLIB.toInt())
                compressed.writeTo(entryData)
            } else {
                val type = input.readByte()
                val payload = ByteArray(input.readInt()).also { input.readFully(it) }
                entryData.writeInt(payload.size + 1)
                entryData.writeByte(type.toInt())
                entryData.write(payload)
            }
            entryData.flush()
            val sectors = (entry.size() + SECTOR - 1) / SECTOR
            require(sectors <= 255) { "chunk $slot needs $sectors sectors" }
            header.putInt(slot * 4, (nextSector shl 8) or sectors)
            header.putInt(SECTOR + slot * 4, timestamp)
            entry.writeTo(body)
            val pad = sectors * SECTOR - entry.size()
            if (pad > 0) body.write(ByteArray(pad))
            nextSector += sectors
        }
        return header.array() + body.toByteArray()
    }

    /** Region-format files: block regions plus the entity and POI regions of every dimension. */
    fun isRegionPath(path: String): Boolean = path.endsWith(".mca")
}
