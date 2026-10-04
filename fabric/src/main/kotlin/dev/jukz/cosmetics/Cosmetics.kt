package dev.jukz.cosmetics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import net.minecraft.client.MinecraftClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Client side of jukz cosmetics, against the rendezvous `/v1/cosmetics` routes (see the Worker's
 * cosmetics.ts).
 *
 * - **Others' badges:** [badgeFor] answers from a cache and queues unknown players; queued ids go out
 *   in one batched lookup every half second, and answers are kept for [TTL_MS] (a player with no
 *   answer simply has no badge — they don't use jukz, or hid it).
 * - **Your own:** [signIn] proves your Minecraft account the way joining a server does (the Worker's
 *   challenge → Mojang `joinServer` → the Worker asks Mojang `hasJoined`), which also registers you, so
 *   your badge shows up for others. [equip] changes it.
 *
 * Every item is free today. Paid ones are already modelled end to end (prices, owned vs locked), so
 * selling one later is a catalog change plus an entitlement grant on the Worker — no mod update.
 * Everything here runs off the render thread; the UI reads the volatile state.
 */
object Cosmetics {
    private const val TTL_MS = 5 * 60_000L
    private const val TOKEN_HEADER = "x-jukz-cosmetics-token"

    /** The badge pick that hides your badge from others. */
    const val NO_BADGE = "none"

    @Volatile var catalog: CosmeticCatalog = CosmeticCatalog.bundled()
        private set

    sealed interface Account {
        data object SignedOut : Account
        data object SigningIn : Account
        data class SignedIn(val id: UUID, val token: String, val expiresAt: Long, val equipped: String?, val owned: Set<String>) : Account
        data class Failed(val reason: String) : Account
    }

    @Volatile var account: Account = Account.SignedOut
        private set

    private class Known(val badge: String?, val at: Long)

    private val known = ConcurrentHashMap<UUID, Known>()
    private val queued = ConcurrentHashMap.newKeySet<UUID>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jukz-cosmetics").apply { isDaemon = true }
    }
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var catalogFetched = false

    init {
        worker.scheduleWithFixedDelay({ runCatching { flushLookups() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    val enabled: Boolean get() = JukzConfig.rendezvousUrl != null

    /** The badge [player] shows, or null (none / not known yet — a lookup is then queued). */
    fun badgeFor(player: UUID): CosmeticCatalog.Badge? {
        if (!enabled) return null
        val entry = known[player]
        if (entry == null || System.currentTimeMillis() - entry.at > TTL_MS) queued += player
        return catalog.item(entry?.badge)
    }

    /** Sign in (once per session token) — call when it may matter: joining a world, opening the screen. */
    fun ensureSignedIn() {
        val current = account
        if (!enabled || current is Account.SigningIn) return
        if (current is Account.SignedIn && current.expiresAt - System.currentTimeMillis() > 60_000) return
        account = Account.SigningIn
        worker.execute { account = runCatching { signIn() }.getOrElse { Account.Failed(it.message ?: "sign-in failed") } }
        refreshCatalog()
    }

    /** Pick [item] (or [NO_BADGE]); [done] runs on the worker thread with an error message or null. */
    fun equip(item: String, done: (String?) -> Unit = {}) {
        val current = account as? Account.SignedIn ?: return done("not signed in")
        worker.execute {
            val error = runCatching {
                val body = JsonObject().apply { addProperty("item", item) }
                val answer = call("/equip", body, current.token)
                val equipped = answer.get("equipped").asString
                account = current.copy(equipped = equipped)
                known[current.id] = Known(equipped.takeUnless { it == NO_BADGE }, System.currentTimeMillis())
                null
            }.getOrElse { it.message ?: "couldn't save" }
            done(error)
        }
    }

    /** The badge you show right now (your pick, or the default). */
    fun myBadge(): CosmeticCatalog.Badge? {
        val me = account as? Account.SignedIn ?: return null
        if (me.equipped == NO_BADGE) return null
        return catalog.item(me.equipped) ?: catalog.item(catalog.defaultBadge)
    }

    // ---- network ------------------------------------------------------------------------------

    private fun signIn(): Account {
        val client = MinecraftClient.getInstance()
        val session = client.session
        val challenge = call("/challenge", JsonObject(), null)
        val serverId = challenge.get("serverId").asString
        // Tell Mojang we're "joining" serverId; the Worker then asks Mojang to confirm it. Offline
        // accounts can't, and only a local dev Worker accepts them.
        runCatching { client.sessionService.joinServer(session.uuidOrNull, session.accessToken, serverId) }
            .onFailure { JukzMod.logger.info("jukz: cosmetics sign-in without Mojang ({})", it.message) }
        val answer = call("/session", JsonObject().apply {
            addProperty("challenge", challenge.get("challenge").asString)
            addProperty("name", session.username)
            session.uuidOrNull?.let { addProperty("id", it.toString()) }
        }, null)
        val token = answer.get("token").asString
        val me = get("/me", token)
        val id = UUID.fromString(answer.get("id").asString)
        val equipped = me.get("equipped")?.takeIf { !it.isJsonNull }?.asString
        known.remove(id) // re-read our own badge with the rest
        return Account.SignedIn(
            id = id,
            token = token,
            expiresAt = answer.get("expiresAt").asLong,
            equipped = equipped,
            owned = me.getAsJsonArray("owned").map { it.asString }.toSet(),
        )
    }

    private fun refreshCatalog() {
        if (catalogFetched) return
        worker.execute {
            runCatching {
                val text = send(request("/catalog").GET())
                catalog = CosmeticCatalog.parse(text)
                catalogFetched = true
            }.onFailure { JukzMod.logger.info("jukz: using the bundled cosmetics catalog ({})", it.message) }
        }
    }

    private fun flushLookups() {
        if (queued.isEmpty() || !enabled) return
        val batch = queued.take(100).also { queued.removeAll(it.toSet()) }
        val now = System.currentTimeMillis()
        // Mark them first so a failed lookup isn't retried every half second.
        batch.forEach { known[it] = Known(known[it]?.badge, now) }
        val answer = JsonParser.parseString(send(request("/players?ids=" + batch.joinToString(",")).GET())).asJsonObject
        val players = answer.getAsJsonObject("players")
        batch.forEach { id -> known[id] = Known(players.get(id.toString())?.asString, now) }
        // A badge newer than our catalog: fetch the live one (once per session).
        if (players.entrySet().any { catalog.item(it.value.asString) == null }) refreshCatalog()
    }

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("${JukzConfig.rendezvousUrl}/v1/cosmetics$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
        JukzConfig.rendezvousAuthToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun call(path: String, body: JsonObject, token: String?): JsonObject {
        val builder = request(path).POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        token?.let { builder.header(TOKEN_HEADER, it) }
        return JsonParser.parseString(send(builder)).asJsonObject
    }

    private fun get(path: String, token: String): JsonObject =
        JsonParser.parseString(send(request(path).GET().header(TOKEN_HEADER, token))).asJsonObject

    /** The body of a 200, or an exception carrying the server's message. */
    private fun send(builder: HttpRequest.Builder): String {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) return response.body()
        val message = runCatching { JsonParser.parseString(response.body()).asJsonObject.get("message").asString }.getOrNull()
        throw IllegalStateException(message ?: "HTTP ${response.statusCode()}")
    }
}
