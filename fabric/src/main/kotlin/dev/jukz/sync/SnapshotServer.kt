package dev.jukz.sync

import com.sun.net.httpserver.HttpServer
import dev.jukz.JukzMod
import dev.jukz.core.discovery.SnapshotOffer
import dev.jukz.core.sync.WorldSync
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.pack.PackWriter
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serves the host's current world save as a one-shot JGit pack over an ephemeral HTTP server, so a
 * guest can pull it and take over hosting when the host leaves (F4-A). On the host side:
 *  1. [WorldSync.commit] flushes the live save into the JGit repo.
 *  2. an HTTP server binds a random free port and serves the pack (all objects reachable from HEAD)
 *     at `GET /snapshot?token=<token>`; any other token is rejected 403. The HEAD commit id rides
 *     back in the [SnapshotProtocol.HEADER_HEAD] header so the guest knows what to reset to.
 *  3. [offer] carries `host:port:token` for the host to publish into its [dev.jukz.core.discovery.WorldRecord].
 *  4. [awaitDownload] blocks until a guest finishes a download (or the timeout elapses); [close] then
 *     shuts the server down before the host withdraws.
 *
 * The host string is supplied by the caller (the LAN address the host already announces) — this class
 * never resolves an address itself, and binds `0.0.0.0` so forwarded/LAN traffic reaches it. It is
 * Minecraft-free, so it is unit-tested against temp repos.
 *
 * Caveat: the ephemeral port is not opened via UPnP, so a cross-internet guest cannot reach it
 * without a manual forward; the handoff is reliable on the LAN (and same-machine), and degrades to
 * the guest's local copy otherwise.
 */
class SnapshotServer private constructor(
    private val server: HttpServer,
    val offer: SnapshotOffer,
    private val downloaded: CountDownLatch,
) {

    /** Blocks until a guest completes a download or [timeoutMs] elapses; true if a download happened. */
    fun awaitDownload(timeoutMs: Long): Boolean = downloaded.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun close() {
        runCatching { server.stop(0) }
    }

    private class Pack(val bytes: ByteArray, val head: ObjectId)

    companion object {
        private val RNG = SecureRandom()

        /**
         * Commit the save and start serving its pack. [host] is the address a guest will dial (the
         * same IP as the game endpoint, a different port). Returns null when there is nothing to serve
         * (commit failed, or the repo has no commit), so the caller can just withdraw.
         */
        fun serve(saveDir: Path, sync: WorldSync, host: String): SnapshotServer? {
            val pack = runCatching { buildPack(saveDir, sync) }.getOrElse {
                JukzMod.logger.warn("jukz: snapshot pack build failed ({} / cause: {}); no handoff offer", it.message, it.cause?.message)
                null
            } ?: return null

            val token = randomToken()
            val latch = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
            server.createContext(SnapshotProtocol.PATH) { exchange ->
                try {
                    val provided = tokenOf(exchange.requestURI.rawQuery)
                    if (provided != token) {
                        exchange.sendResponseHeaders(403, -1)
                        return@createContext
                    }
                    exchange.responseHeaders.add(SnapshotProtocol.HEADER_HEAD, pack.head.name)
                    exchange.sendResponseHeaders(200, pack.bytes.size.toLong())
                    exchange.responseBody.use { it.write(pack.bytes) }
                    latch.countDown()
                } catch (e: Exception) {
                    JukzMod.logger.warn("jukz: snapshot serve error ({})", e.message)
                } finally {
                    exchange.close()
                }
            }
            server.executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "jukz-snapshot").apply { isDaemon = true }
            }
            server.start()
            val port = server.address.port
            JukzMod.logger.info("jukz: serving world snapshot on {}:{} (head {})", host, port, pack.head.name)
            return SnapshotServer(server, SnapshotOffer(host, port, token), latch)
        }

        /** Commit the live save, then build a pack of every object reachable from HEAD. */
        private fun buildPack(saveDir: Path, sync: WorldSync): Pack? {
            val generation = sync.currentGeneration(saveDir)
            runBlocking { sync.commit(saveDir, generation) }
            Git.open(saveDir.toFile()).use { git ->
                val head = git.repository.resolve("HEAD") ?: return null
                val out = ByteArrayOutputStream()
                PackWriter(git.repository).use { pw ->
                    pw.preparePack(NullProgressMonitor.INSTANCE, setOf(head), emptySet<ObjectId>())
                    pw.writePack(NullProgressMonitor.INSTANCE, NullProgressMonitor.INSTANCE, out)
                }
                return Pack(out.toByteArray(), head)
            }
        }

        private fun tokenOf(rawQuery: String?): String? =
            rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0 && pair.substring(0, eq) == SnapshotProtocol.PARAM_TOKEN) pair.substring(eq + 1) else null
            }

        private fun randomToken(): String =
            ByteArray(32).also { RNG.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}
