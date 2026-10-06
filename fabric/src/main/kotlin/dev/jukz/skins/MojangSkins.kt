package dev.jukz.skins

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Changing the skin of a Mojang (premium) account, the way minecraft.net and the launchers do: Mojang's
 * public skin API, authorized with this game session's token. The token goes to
 * api.minecraftservices.com only — never to a jukz server, never logged.
 */
object MojangSkins {
    private const val API = "https://api.minecraftservices.com/minecraft/profile"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    enum class Account { CHECKING, PREMIUM, OFFLINE }

    /** Whether this session is a real Mojang account; asked once, the first time the Skin section opens. */
    @Volatile var account = Account.CHECKING
        private set
    private var asked = false

    fun check() {
        if (asked) return
        asked = true
        val token = token() ?: run { account = Account.OFFLINE; return }
        Thread({
            account = runCatching {
                val response = http.send(request(API).GET().build(), HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() == 200) Account.PREMIUM else Account.OFFLINE
            }.getOrDefault(Account.OFFLINE)
        }, "jukz-skin-check").apply { isDaemon = true }.start()
    }

    /** Upload [skin] as this account's skin; [done] gets null on success or what went wrong (any thread). */
    fun upload(skin: LocalSkins.Skin, done: (String?) -> Unit) {
        val token = token() ?: return done("This Minecraft session has no Mojang account.")
        Thread({
            done(runCatching {
                val boundary = "jukz" + UUID.randomUUID().toString().replace("-", "")
                val body = multipart(boundary, if (skin.slim) "slim" else "classic", skin.png)
                val response = http.send(
                    request("$API/skins").header("Content-Type", "multipart/form-data; boundary=$boundary")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                problem(response.statusCode(), response.body())
            }.getOrElse { "Couldn't reach Mojang: ${it.message}" })
        }, "jukz-skin-upload").apply { isDaemon = true }.start()
    }

    /** Back to the default skin (Steve/Alex) on the account. */
    fun reset(done: (String?) -> Unit) {
        val token = token() ?: return done("This Minecraft session has no Mojang account.")
        Thread({
            done(runCatching {
                val response = http.send(request("$API/skins/active").DELETE().build(), HttpResponse.BodyHandlers.ofString())
                problem(response.statusCode(), response.body())
            }.getOrElse { "Couldn't reach Mojang: ${it.message}" })
        }, "jukz-skin-reset").apply { isDaemon = true }.start()
    }

    private fun problem(status: Int, body: String): String? = when (status) {
        in 200..299 -> null
        401 -> "Your Minecraft session expired. Restart the game and try again."
        429 -> "Mojang says too many skin changes. Wait a few minutes."
        400 -> runCatching { JsonParser.parseString(body).asJsonObject["errorMessage"].asString }.getOrNull()
            ?: "Mojang didn't accept that image."
        else -> "Mojang answered $status."
    }

    private fun request(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", "Bearer ${token()}")

    /** The session's access token, or null when it can't be a Mojang one (offline / dev accounts). */
    private fun token(): String? = Minecraft.getInstance().user.accessToken.takeIf { it.length > 32 }

    private fun multipart(boundary: String, variant: String, png: ByteArray): ByteArray {
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n$variant\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        return head.toByteArray() + png + tail.toByteArray()
    }
}
