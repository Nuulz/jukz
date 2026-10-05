package dev.jukz.sync

import dev.jukz.compat.currentGame
import dev.jukz.core.model.GameVersion
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.client.CloudWorlds
import dev.jukz.config.JukzConfig
import dev.jukz.core.model.WorldId
import dev.jukz.core.model.WorldKey
import dev.jukz.world.WorldKeyStore
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

    /**
     * The ghost's head metadata: the fencing [generation], the [commit] id its pack resets to, and the
     * Minecraft version it was saved on ([game]; null in heads from older mods, which ran 1.21.1).
     */
    data class GhostHead(val generation: Long, val commit: String, val game: GameVersion? = null)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    private val uploadTimeout = Duration.ofMinutes(10)
    private val signTimeout = Duration.ofSeconds(10)

    /** True if a rendezvous is configured at all (else there is nowhere to sign URLs). */
    fun isConfigured(): Boolean = JukzConfig.rendezvousUrl != null

    /** How a cloud upload ended: done, worth retrying (network), or refused by a limit (don't retry). */
    sealed interface UploadResult {
        data object Done : UploadResult
        data object Retry : UploadResult
        data class Refused(val message: String) : UploadResult
    }

    /** Thrown inside [uploadGhost] when the rendezvous refuses the upload for a limit. */
    private class Refusal(message: String) : Exception(message)

    /**
     * Upload the world [pack] and [head] for [worldId] at [generation]. [onProgress] is called with
     * (bytesSent, totalBytes) as the pack uploads.
     */
    fun uploadGhost(
        worldId: WorldId,
        generation: Long,
        pack: ByteArray,
        head: String,
        onProgress: (Long, Long) -> Unit,
    ): UploadResult {
        val base = JukzConfig.rendezvousUrl ?: return UploadResult.Refused("no rendezvous server configured")
        return runCatching {
            // Signing is inside the catch too: a rendezvous blip (connection refused) must fail this
            // attempt so the caller retries, not kill the upload thread mid-"retrying" screen.
            val urls = signUpload(base, worldId, generation, head, pack.size) ?: return UploadResult.Retry
            putBytes(urls.packUrl, pack, onProgress)
            // The head object carries the fencing generation alongside the commit id ("<gen> <commit>"),
            // so a direct-open can compare it to the local copy without downloading the whole pack.
            // ... then the Minecraft version it was saved on ("<gen> <commit> <dataVersion> <name>"); older
            // mods read the first two fields and ignore the rest.
            putBytes(urls.headUrl, "$generation $head ${currentGame.dataVersion} ${currentGame.name}".toByteArray(Charsets.UTF_8)) { _, _ -> }
            UploadResult.Done
        }.getOrElse {
            if (it is Refusal) {
                JukzMod.logger.warn("jukz: cloud backup refused: {}", it.message)
                return UploadResult.Refused(it.message ?: "refused")
            }
            JukzMod.logger.warn("jukz: ghost snapshot upload failed ({})", it.message)
            UploadResult.Retry
        }
    }

    /** Ask the rendezvous for the download URLs, or null when disabled / unreachable. */
    fun ghostSnapshot(worldId: WorldId): GhostUrls? {
        val base = JukzConfig.rendezvousUrl ?: return null
        val builder = signed(URI.create("$base/v1/snapshot/${worldId.uuid}")).GET().timeout(signTimeout)
        // A world with a key only hands its cloud copy to someone holding that key (a past host, or a
        // player the host let in); without it the rendezvous answers as if there were no backup.
        WorldKeyStore.keyFor(worldId)
            ?.headers(WorldKey.OP_SNAPSHOT_DOWNLOAD, worldId, "", System.currentTimeMillis())
            ?.forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
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

    /**
     * Probe + parse the ghost head object (small text "<generation> <commit>"). Returns null when no
     * ghost is present (R2 404) or it is unreadable. A legacy head holding only the commit id parses
     * with generation 0, so a direct-open comparison treats it as not-newer than any real local copy.
     */
    fun ghostHead(headUrl: String): GhostHead? {
        val text = downloadText(headUrl)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return parseHead(text)
    }

    /** "<generation> <commit> [<dataVersion> <name>]", or a legacy head holding only the commit id. */
    fun parseHead(text: String): GhostHead {
        val parts = text.trim().split(Regex("\\s+"))
        val gen = if (parts.size >= 2) parts[0].toLongOrNull() else null
        if (gen == null) return GhostHead(0L, parts.last())
        val data = parts.getOrNull(2)?.toIntOrNull()
        val name = parts.drop(3).joinToString(" ")
        val game = if (data != null && data > 0 && name.isNotBlank()) GameVersion(name, data) else null
        return GhostHead(gen, parts[1], game)
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

    private fun signUpload(base: String, worldId: WorldId, generation: Long, commit: String, size: Int): GhostUrls? {
        // The size lets the rendezvous refuse a backup over the limit before any byte is sent; the commit
        // lets it refuse a same-generation fork from overwriting the canonical copy (split-brain fence).
        val body = """{"worldId":"${worldId.uuid}","generation":$generation,"commit":"$commit","size":$size}"""
        val builder = signed(URI.create("$base/v1/snapshot/upload-url"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(signTimeout)
        // Only the world's key holder may replace its cloud copy (see WorldKey).
        WorldKeyStore.keyFor(worldId)
            ?.headers(WorldKey.OP_SNAPSHOT_UPLOAD, worldId, body, System.currentTimeMillis())
            ?.forEach { (name, value) -> builder.header(name, value) }
        // Signed in with a Microsoft account: the backup is also remembered on that account, so the
        // player's other PCs bring the world over (see CloudWorlds).
        CloudWorlds.uploadHeaders(worldId).forEach { (name, value) -> builder.header(name, value) }
        val request = builder.build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 409) {
            throw Refusal("the cloud already has a newer copy of this world (open it to get it first)")
        }
        if (response.statusCode() == 413 || response.statusCode() == 429) {
            val message = runCatching { JsonParser.parseString(response.body()).asJsonObject.get("message").asString }.getOrNull()
            throw Refusal(message ?: "over the cloud backup limit")
        }
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
        if (response.statusCode() == 413) throw Refusal("this world is over the cloud backup size limit")
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
            private var demand = 0L
            private var emitting = false
            private var cancelled = false
            private var completed = false

            override fun request(n: Long) {
                if (cancelled || completed || n <= 0) return
                demand += n
                // `java.net.http` re-enters request() from *inside* onNext() (notably over TLS, via the
                // SSLTube). Guard against that: if a drain loop is already running in an outer frame,
                // just leave the added demand for it. Recursing here re-reads state mid-emit and resends
                // the in-flight chunk — a single-chunk PUT (e.g. the 40-byte head) went out as 80 bytes
                // ("Too many bytes in request body. Expected: 40, got: 80"), and a large body could
                // overflow the stack. We also advance `offset` *before* onNext so any re-entrant
                // request() that does slip through observes the post-emit cursor, never the old one.
                if (emitting) return
                emitting = true
                try {
                    while (demand > 0 && offset < data.size && !cancelled) {
                        val start = offset
                        val chunk = minOf(64 * 1024, data.size - start)
                        offset = start + chunk
                        demand--
                        subscriber.onNext(java.nio.ByteBuffer.wrap(data, start, chunk))
                        onProgress(offset.toLong(), data.size.toLong())
                    }
                    if (offset >= data.size && !completed && !cancelled) {
                        completed = true // Reactive-Streams 1.7: onComplete signalled exactly once
                        subscriber.onComplete()
                    }
                } finally {
                    emitting = false
                }
            }

            override fun cancel() {
                cancelled = true
            }
        })
    }
}
