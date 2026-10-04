package dev.jukz.sync

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.random.Random

class RegionCodecTest {

    /** A region file with the given chunks: slot → (compression type, payload as stored). */
    private fun region(chunks: Map<Int, Pair<Int, ByteArray>>, timestamps: Map<Int, Int> = emptyMap()): ByteArray {
        val header = ByteBuffer.allocate(8192)
        val body = ByteArrayOutputStream()
        var sector = 2
        for ((slot, entry) in chunks.toSortedMap()) {
            val (type, payload) = entry
            val e = ByteBuffer.allocate(5 + payload.size).putInt(payload.size + 1).put(type.toByte()).put(payload).array()
            val sectors = (e.size + 4095) / 4096
            header.putInt(slot * 4, (sector shl 8) or sectors)
            header.putInt(4096 + slot * 4, timestamps[slot] ?: 1_700_000_000)
            body.write(e); body.write(ByteArray(sectors * 4096 - e.size))
            sector += sectors
        }
        return header.array() + body.toByteArray()
    }

    private fun zlib(b: ByteArray) = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    /** slot → (timestamp, type, decompressed bytes or raw payload) as Minecraft would read it. */
    private fun chunksOf(region: ByteArray): Map<Int, Triple<Int, Int, List<Byte>>> {
        val buf = ByteBuffer.wrap(region)
        return (0 until 1024).mapNotNull { slot ->
            val loc = buf.getInt(slot * 4)
            if (loc == 0) return@mapNotNull null
            val start = (loc ushr 8) * 4096
            val len = buf.getInt(start)
            val type = region[start + 4].toInt()
            val payload = region.copyOfRange(start + 5, start + 4 + len)
            val content = if (type == 2) InflaterInputStream(payload.inputStream()).readBytes() else payload
            slot to Triple(buf.getInt(4096 + slot * 4), if (type == 1) 2 else type, content.toList())
        }.toMap()
    }

    private fun nbtLike(seed: Int, size: Int): ByteArray {
        val r = Random(seed)
        return ByteArray(size) { i -> if (i % 7 == 0) r.nextInt().toByte() else (i % 13).toByte() }
    }

    @Test
    fun `every chunk survives open and close, whatever its compression`() {
        val original = region(
            mapOf(
                0 to (2 to zlib(nbtLike(1, 20_000))),
                5 to (1 to gzip(nbtLike(2, 9_000))),          // gzip comes back as zlib, same content
                77 to (3 to nbtLike(3, 3_000)),               // uncompressed: kept as is
                1023 to ((2 or 128) to ByteArray(0)),         // external .mcc chunk: kept as is
            ),
            timestamps = mapOf(0 to 11, 5 to 22, 77 to 33, 1023 to 44),
        )
        val open = RegionCodec.open(original)!!
        assertTrue(RegionCodec.isOpen(open))
        val closed = RegionCodec.close(open)
        val before = chunksOf(original).mapValues { (_, v) -> if (v.second == 1) v.copy(second = 2) else v }
        val gz = chunksOf(original)[5]!!
        val expected = before + (5 to Triple(gz.first, 2, java.util.zip.GZIPInputStream(region(mapOf(0 to (1 to gzip(nbtLike(2, 9_000))))).let { r ->
            val b = ByteBuffer.wrap(r); val s = (b.getInt(0) ushr 8) * 4096; r.copyOfRange(s + 5, s + 4 + b.getInt(s)) }.inputStream()).readBytes().toList()))
        assertEquals(expected, chunksOf(closed))
        assertEquals(0, closed.size % 4096, "whole sectors")
    }

    @Test
    fun `open is deterministic, so unchanged regions dedupe in git`() {
        val r = region(mapOf(3 to (2 to zlib(nbtLike(9, 5_000)))))
        assertArrayEquals(RegionCodec.open(r), RegionCodec.open(r))
    }

    @Test
    fun `odd files are left alone, and close passes through anything not open`() {
        assertNull(RegionCodec.open(ByteArray(0)))
        assertNull(RegionCodec.open(ByteArray(100)))
        val garbage = ByteArray(8192 + 4096).also { ByteBuffer.wrap(it).putInt(0, (2 shl 8) or 1).putInt(8192, 50).put(8196, 2) }
        assertNull(RegionCodec.open(garbage), "a broken zlib payload keeps the file as it was")
        val plain = region(mapOf(1 to (2 to zlib(nbtLike(4, 100)))))
        assertArrayEquals(plain, RegionCodec.close(plain), "snapshots from before this format apply unchanged")
    }

    @Test
    fun `snapshot codec reads xz and plain packs alike`(@TempDir dir: Path) {
        val pack = dir.resolve("p.pack")
        val data = ByteArray(20 shl 20) { (it % 251).toByte() } // spans several parallel blocks
        Files.write(pack, data)
        val xz = dir.resolve("p.xz")
        Files.write(xz, SnapshotCodec.compress(pack))
        assertTrue(Files.size(xz) < data.size / 50)
        SnapshotCodec.open(xz).use { assertArrayEquals(data, it.readBytes()) }
        SnapshotCodec.open(pack).use { assertArrayEquals(data, it.readBytes()) }
    }
}
