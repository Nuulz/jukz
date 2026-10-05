package dev.jukz.discovery

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.guard.DeviceKey
import dev.jukz.core.guard.ProofOfWork
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * This install's anti-abuse identity on disk and with the rendezvous: the [DeviceKey]
 * (`config/jukz-device.key`, made on first launch) and whether the rendezvous has registered it
 * (`config/jukz-device.registered` holds the rendezvous URL it registered with).
 *
 * Registering is silent: a premium account proves itself with its cosmetics session (Mojang-verified);
 * otherwise the client solves a ~1 s [ProofOfWork] puzzle, once per install, in the background.
 */
object DeviceIdentity {
    private const val KEY_FILE = "jukz-device.key"
    private const val REGISTERED_FILE = "jukz-device.registered"

    /** The signed-in cosmetics session (premium players), set by the client; null on a dedicated server. */
    @Volatile var sessionToken: () -> String? = { null }

    /** Tell the player the rendezvous refused for [Long] seconds (set by the client). */
    @Volatile var onLimited: (Long) -> Unit = {}

    @Volatile private var noticeUntil = 0L

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val key: DeviceKey? by lazy {
        runCatching {
            val file = configDir().resolve(KEY_FILE)
            if (Files.exists(file)) {
                DeviceKey.decode(Files.readString(file))
            } else {
                DeviceKey.generate().also {
                    Files.createDirectories(file.parent)
                    Files.writeString(file, it.encode())
                    JukzMod.logger.info("jukz: created this device's key")
                }
            }
        }.onFailure { JukzMod.logger.warn("jukz: no device key ({}); calls go unsigned", it.message) }.getOrNull()
    }

    /** Device signature headers (plus the premium session, which raises the limits) for one call. */
    fun headers(method: String, path: String, body: String): Map<String, String> {
        val k = key ?: return emptyMap()
        val headers = k.headers(method, path, body, System.currentTimeMillis()).toMutableMap()
        sessionToken()?.let { headers[SESSION_HEADER] = it }
        return headers
    }

    /** Register in the background if this rendezvous hasn't registered the key yet (called at startup). */
    fun ensureRegisteredAsync(base: String) {
        if (registeredWith(base)) return
        Thread { ensureRegistered(base) }.apply { isDaemon = true; name = "jukz-device-register" }.start()
    }

    /** The rendezvous forgot us (a reset, or a new server): drop the marker so the next call registers. */
    fun forget() {
        runCatching { Files.deleteIfExists(configDir().resolve(REGISTERED_FILE)) }
    }

    /** Register now (blocking, ~1 s without a premium session). True when the rendezvous counts this key. */
    @Synchronized
    fun ensureRegistered(base: String): Boolean {
        if (registeredWith(base)) return true
        val k = key ?: return false
        return runCatching {
            val premium = sessionToken() != null
            val body = JsonObject()
            if (!premium) {
                val challenge = JsonParser.parseString(post(base, "/v1/device/challenge", "{}", signed = false).body()).asJsonObject
                val text = challenge.get("challenge").asString
                val bits = challenge.get("bits").asInt
                val started = System.currentTimeMillis()
                val nonce = ProofOfWork.solve(text, k.publicKeyText(), bits) ?: return false
                JukzMod.logger.info("jukz: device puzzle solved in {} ms", System.currentTimeMillis() - started)
                body.addProperty("challenge", text)
                body.addProperty("nonce", nonce)
            }
            val answer = post(base, "/v1/device/register", body.toString(), signed = true)
            if (answer.statusCode() == 200) {
                Files.writeString(configDir().resolve(REGISTERED_FILE), base)
                JukzMod.logger.info("jukz: this device is registered with the rendezvous{}", if (premium) " (premium)" else "")
                true
            } else {
                JukzMod.logger.warn("jukz: device registration returned HTTP {}: {}", answer.statusCode(), answer.body())
                false
            }
        }.onFailure { JukzMod.logger.warn("jukz: device registration failed ({})", it.message) }.getOrDefault(false)
    }

    /** Show the "too many worlds" notice at most once per refusal period. */
    fun noticeLimited(retryAfterSecs: Long) {
        val now = System.currentTimeMillis()
        if (now < noticeUntil) return
        noticeUntil = now + retryAfterSecs * 1000
        runCatching { onLimited(retryAfterSecs) }
    }

    private fun registeredWith(base: String): Boolean = runCatching {
        val file = configDir().resolve(REGISTERED_FILE)
        Files.exists(file) && Files.readString(file).trim() == base
    }.getOrDefault(false)

    private fun post(base: String, path: String, body: String, signed: Boolean): HttpResponse<String> {
        val uri = URI.create(base + path)
        val builder = HttpRequest.newBuilder(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(10))
        JukzConfig.rendezvousAuthToken?.let { builder.header("Authorization", "Bearer $it") }
        if (signed) headers("POST", uri.rawPath, body).forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun configDir(): Path = FabricLoader.getInstance().configDir

    private const val SESSION_HEADER = "x-jukz-cosmetics-token"
}
