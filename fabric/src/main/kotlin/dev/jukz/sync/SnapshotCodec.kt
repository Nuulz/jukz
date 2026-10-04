package dev.jukz.sync

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The bytes a snapshot travels as (cloud backup and live handoff alike): the git pack, written with no
 * compression of its own over "open" regions (see [RegionCodec]), compressed with xz as a whole. A fresh
 * world goes from ~9.5 MB (packed .mca files) to ~4.7 MB, with nothing left out.
 *
 * xz is single-threaded, so the pack is cut into [BLOCK] pieces compressed in parallel and written as
 * concatenated xz streams — a valid .xz any decoder reads in one go, at almost the same size. Readers
 * accept both this and a plain pack ([open]), so snapshots from before keep working.
 */
object SnapshotCodec {
    private const val BLOCK = 8 shl 20

    /** How hard to compress: a live handoff has a player waiting, a cloud backup is kept and paid for. */
    enum class Level(val preset: Int) {
        /** ~5.5 MB in under a second (fresh world): the handoff, where the next host is waiting. */
        FAST(1),
        /** ~4.7 MB in a few seconds: cloud backups, stored for good. */
        SMALL(6),
    }
    private val XZ_MAGIC = byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0)

    /** Encoder threads: each needs ~90 MB at preset 6, so a few at most inside the game's heap. */
    private val threads = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(1, 3) }

    /** Compress the pack file at [pack]. */
    fun compress(pack: Path, level: Level = Level.SMALL): ByteArray {
        val size = Files.size(pack)
        val blocks = ((size + BLOCK - 1) / BLOCK).toInt().coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "jukz-xz").apply { isDaemon = true } }
        try {
            val parts = (0 until blocks).map { i ->
                pool.submit(Callable {
                    val start = i.toLong() * BLOCK
                    val length = minOf(BLOCK.toLong(), size - start).toInt()
                    val chunk = ByteArray(length)
                    Files.newByteChannel(pack).use { ch ->
                        ch.position(start)
                        val bb = java.nio.ByteBuffer.wrap(chunk)
                        while (bb.hasRemaining() && ch.read(bb) >= 0) Unit
                    }
                    val out = ByteArrayOutputStream(length / 3)
                    XZOutputStream(out, LZMA2Options(level.preset)).use { it.write(chunk) }
                    out.toByteArray()
                })
            }
            val result = ByteArrayOutputStream()
            parts.forEach { result.write(it.get()) }
            return result.toByteArray()
        } finally {
            pool.shutdownNow()
        }
    }

    fun isCompressed(head: ByteArray): Boolean = head.size >= XZ_MAGIC.size && XZ_MAGIC.indices.all { head[it] == XZ_MAGIC[it] }

    /** The pack in the snapshot file at [path], decompressing it if it is xz. */
    fun open(path: Path): InputStream {
        val input = BufferedInputStream(Files.newInputStream(path))
        input.mark(XZ_MAGIC.size)
        val head = input.readNBytes(XZ_MAGIC.size)
        input.reset()
        return if (isCompressed(head)) BufferedInputStream(XZInputStream(input)) else input
    }
}
