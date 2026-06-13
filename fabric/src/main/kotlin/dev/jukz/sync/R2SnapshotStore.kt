package dev.jukz.sync

import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.model.WorldId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Client side of the ghost-takeover snapshot store. The rendezvous signs the R2 URLs; this adapter
 * uploads (host, on a guest-less close) and downloads (guest, taking over a world with no live host)
 * the world pack + head straight to/from R2. Network adapter — validated in-game, like
 * [dev.jukz.discovery.RendezvousWorldRegistry]. Every method is best-effort: a failure logs and
 * returns false/null so it never blocks play.
 */
object R2SnapshotStore {

    data class GhostUrls(val packUrl: String, val headUrl: String)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    private val uploadTimeout = Duration.ofMinutes(10)
    private val signTimeout = Duration.ofSeconds(10)

    /** True if a rendezvous is configured at all (else there is nowhere to sign URLs). */
    fun isConfigured(): Boolean = JukzConfig.rendezvousUrl != null

    /**
     * Upload the world [pack] and [head] for [worldId] at [generation]. [onProgress] is called with
     * (bytesSent, totalBytes) as the pack uploads. Returns true only when both objects PUT with a 2xx.
     */
    fun uploadGhost(
        worldId: WorldId,
        generation: Long,
        pack: ByteArray,
        head: String,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val base = JukzConfig.rendezvousUrl ?: return false
        val urls = signUpload(base, worldId, generation) ?: return false
        return runCatching {
            putBytes(urls.packUrl, pack, onProgress)
            putBytes(urls.headUrl, head.toByteArray(Charsets.UTF_8)) { _, _ -> }
            true
        }.getOrElse {
            JukzMod.logger.warn("jukz: ghost snapshot upload failed ({})", it.message)
            false
        }
    }

    /** Ask the rendezvous for the download URLs, or null when disabled / unreachable. */
    fun ghostSnapshot(worldId: WorldId): GhostUrls? {
        val base = JukzConfig.rendezvousUrl ?: return null
        val request = signed(URI.create("$base/v1/snapshot/${worldId.uuid}")).GET()
            .timeout(signTimeout).build()
        return runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@runCatching null
            val o = JsonParser.parseString(response.body()).asJsonObject
            GhostUrls(o.get("packUrl").asString, o.get("headUrl").asString)
        }.getOrElse {
            JukzMod.logger.warn("jukz: ghost snapshot lookup failed ({})", it.message)
            null
        }
    }

    /** GET a small text object (the head commit id). Returns its trimmed content, or null on 404/error. */
    fun downloadText(url: String): String? = runCatching {
        val response = http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().timeout(signTimeout).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() == 200) response.body().trim() else null
    }.getOrNull()

    /** GET the pack object into a temp file, reporting (bytesRead, totalBytes). Null on 404/error. */
    fun downloadToTemp(url: String, onProgress: (Long, Long) -> Unit): Path? = runCatching {
        val dest = Files.createTempFile("jukz-ghost", ".pack")
        try {
            val response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(uploadTimeout).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            if (response.statusCode() != 200) {
                Files.deleteIfExists(dest)
                return@runCatching null
            }
            val total = response.headers().firstValueAsLong("content-length").orElse(-1L)
            response.body().use { input ->
                Files.newOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            dest
        } catch (t: Throwable) {
            Files.deleteIfExists(dest) // never leave a half-written temp behind on a mid-download failure
            throw t // re-thrown so the outer runCatching maps it to null
        }
    }.getOrNull()

    // ---- helpers -------------------------------------------------------------------------

    private fun signUpload(base: String, worldId: WorldId, generation: Long): GhostUrls? {
        val body = """{"worldId":"${worldId.uuid}","generation":$generation}"""
        val request = signed(URI.create("$base/v1/snapshot/upload-url"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(signTimeout).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            JukzMod.logger.info("jukz: snapshot upload-url returned HTTP {}", response.statusCode())
            return null
        }
        val o = JsonParser.parseString(response.body()).asJsonObject
        return GhostUrls(o.get("packUrl").asString, o.get("headUrl").asString)
    }

    private fun putBytes(url: String, bytes: ByteArray, onProgress: (Long, Long) -> Unit) {
        val publisher = CountingBodyPublisher(bytes, onProgress)
        val request = HttpRequest.newBuilder(URI.create(url)).PUT(publisher).timeout(uploadTimeout).build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        require(response.statusCode() in 200..299) { "R2 PUT returned HTTP ${response.statusCode()}" }
    }

    private fun signed(uri: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri)
        JukzConfig.rendezvousAuthToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }
}

/**
 * A [HttpRequest.BodyPublisher] over a fixed byte array that reports how many bytes have been handed
 * to the HTTP client, so the upload screen can show real progress. `java.net.http` has no native
 * upload-progress hook, so we wrap the body and count as we feed the subscriber.
 */
private class CountingBodyPublisher(
    private val data: ByteArray,
    private val onProgress: (Long, Long) -> Unit,
) : java.net.http.HttpRequest.BodyPublisher {

    override fun contentLength(): Long = data.size.toLong()

    override fun subscribe(subscriber: java.util.concurrent.Flow.Subscriber<in java.nio.ByteBuffer>) {
        subscriber.onSubscribe(object : java.util.concurrent.Flow.Subscription {
            private var offset = 0
            private var cancelled = false
            private var completed = false

            override fun request(n: Long) {
                if (cancelled || completed) return
                var remaining = n
                while (remaining > 0 && offset < data.size) {
                    val chunk = minOf(64 * 1024, data.size - offset)
                    subscriber.onNext(java.nio.ByteBuffer.wrap(data, offset, chunk))
                    offset += chunk
                    onProgress(offset.toLong(), data.size.toLong())
                    remaining--
                }
                if (offset >= data.size) {
                    completed = true // Reactive-Streams 1.7: onComplete must be signalled exactly once
                    subscriber.onComplete()
                }
            }

            override fun cancel() {
                cancelled = true
            }
        })
    }
}
