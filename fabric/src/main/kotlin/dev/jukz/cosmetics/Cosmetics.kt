package dev.jukz.cosmetics

import dev.jukz.compat.jukzSessionService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.cosmetics.CosmeticCatalog.Item
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.Signature
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Client side of jukz cosmetics, against the rendezvous `/v1/cosmetics` routes (see the Worker's
 * cosmetics.ts). A player's loadout is one item per [Slot]: the tab-list badge and the 3D hat, face and
 * back pieces drawn on their model.
 *
 * - **Others:** [loadoutFor] answers from a cache and queues unknown players; queued ids go out in one
 *   batched lookup every half second, and answers are kept for [TTL_MS] (a player with no answer wears
 *   nothing — they don't use jukz).
 * - **You:** [signIn] proves your Minecraft account by signing the Worker's challenge with the chat
 *   certificate Mojang gives every Microsoft account (the server-style `joinServer` / `hasJoined`
 *   handshake is the fallback), which also registers you, so others see what you wear. [equip] changes
 *   one slot.
 *
 * Every item is free today. Paid ones are already modelled end to end (prices, owned vs locked), so
 * selling one later is a catalog change plus an entitlement grant on the Worker — no mod update.
 * Everything here runs off the render thread; the UI and renderers read the volatile state.
 */
object Cosmetics {
    private const val TTL_MS = 5 * 60_000L
    private const val RETRY_MS = 60_000L
    private const val TOKEN_HEADER = "x-jukz-cosmetics-token"

    /** The pick that leaves a slot empty (for badges: hides the default one too). */
    const val NO_BADGE = "none"

    @Volatile var catalog: CosmeticCatalog = CosmeticCatalog.bundled()
        private set

    sealed interface Account {
        data object SignedOut : Account
        data object SigningIn : Account
        data class SignedIn(val id: UUID, val token: String, val expiresAt: Long, val picks: Map<Slot, String>, val owned: Set<String>) : Account
        data class Failed(val reason: String) : Account
    }

    @Volatile var account: Account = Account.SignedOut
        private set

    private class Known(val loadout: Map<Slot, String>, val at: Long)

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

    /** What [player] wears (slot → item id; empty = nothing, or not known yet — a lookup is then queued). */
    fun loadoutFor(player: UUID): Map<Slot, String> {
        if (!enabled) return emptyMap()
        (account as? Account.SignedIn)?.takeIf { it.id == player }?.let { return visible(it.picks) }
        val entry = known[player]
        if (entry == null || System.currentTimeMillis() - entry.at > TTL_MS) queued += player
        return entry?.loadout ?: emptyMap()
    }

    /** The badge [player] shows in the tab list, or null. */
    fun badgeFor(player: UUID): Item? = catalog.item(loadoutFor(player)[Slot.BADGE])

    /** What you wear in [slot] right now (your pick, or the default badge), or null. */
    fun wearing(slot: Slot): Item? {
        val me = account as? Account.SignedIn ?: return null
        return catalog.item(visible(me.picks)[slot])
    }

    /** Picks as others see them: the badge defaults to the catalog's; "none" and unknown ids drop out. */
    private fun visible(picks: Map<Slot, String>): Map<Slot, String> = Slot.entries.mapNotNull { slot ->
        val pick = picks[slot]
        when {
            pick == NO_BADGE -> null
            pick != null && catalog.item(pick)?.slot == slot -> slot to pick
            slot == Slot.BADGE -> slot to catalog.defaultBadge
            else -> null
        }
    }.toMap()

    /** The session token of a signed-in (Microsoft) account that is still good for a minute, or null. */
    fun sessionToken(): String? =
        (account as? Account.SignedIn)?.takeIf { it.expiresAt - System.currentTimeMillis() > 60_000 }?.token

    @Volatile private var lastAttempt = 0L

    /**
     * Sign in (once per session token) — call when it may matter: joining a world, opening the screen.
     * After a failure it waits [RETRY_MS] before trying again on its own, so a screen that rebuilds on
     * every state change can't turn into a loop (Mojang's session server rate-limits); [force] is the
     * player pressing "Try again".
     */
    fun ensureSignedIn(force: Boolean = false) {
        val current = account
        if (!enabled || current is Account.SigningIn) return
        if (current is Account.SignedIn && current.expiresAt - System.currentTimeMillis() > 60_000) return
        if (current is Account.Failed && !force && System.currentTimeMillis() - lastAttempt < RETRY_MS) return
        lastAttempt = System.currentTimeMillis()
        account = Account.SigningIn
        worker.execute { account = runCatching { signIn() }.getOrElse { Account.Failed(it.message ?: "sign-in failed") } }
        refreshCatalog()
    }

    /** Put [item] (or [NO_BADGE]) in [slot]; [done] runs on the worker thread with an error message or null. */
    fun equip(slot: Slot, item: String, done: (String?) -> Unit = {}) {
        val current = account as? Account.SignedIn ?: return done("not signed in")
        worker.execute {
            val error = runCatching {
                val body = JsonObject().apply {
                    addProperty("slot", slot.key)
                    addProperty("item", item)
                }
                val answer = call("/equip", body, current.token)
                account = current.copy(picks = current.picks + (slot to answer.get("equipped").asString))
                null
            }.getOrElse { it.message ?: "couldn't save" }
            done(error)
        }
    }

    /** The creators page, as a link that proves this Minecraft account there (verifies the creator account). */
    const val CREATORS_PAGE = "https://nuulm.com/jukz/crear"

    /** [done] gets the creators page URL — with a 15-minute verification link when signed in — on the worker thread. */
    fun creatorPageUrl(done: (String) -> Unit) = pageUrl(null, CREATORS_PAGE, done)

    /** The account page, signed in already when the game is (premium accounts). */
    const val ACCOUNT_PAGE = "https://nuulm.com/jukz/cuenta"

    fun accountPageUrl(done: (String) -> Unit) = pageUrl("account", ACCOUNT_PAGE, done)

    private fun pageUrl(page: String?, fallback: String, done: (String) -> Unit) {
        val me = account as? Account.SignedIn ?: return done(localized(fallback))
        worker.execute {
            val body = JsonObject().apply { page?.let { addProperty("page", it) } }
            done(localized(runCatching { call("/creator-link", body, me.token).get("url").asString }.getOrDefault(fallback)))
        }
    }

    /** The site's Spanish pages (/es/jukz/…) when the game is in Spanish. */
    private fun localized(url: String): String {
        val language = runCatching { Minecraft.getInstance().options.languageCode }.getOrDefault("")
        return if (language.startsWith("es")) url.replace("nuulm.com/jukz/", "nuulm.com/es/jukz/") else url
    }

    // ---- network ------------------------------------------------------------------------------

    private fun signIn(): Account {
        val client = Minecraft.getInstance()
        val session = client.user
        val challenge = call("/challenge", JsonObject(), null)
        val challengeText = challenge.get("challenge").asString
        val body = JsonObject().apply {
            addProperty("challenge", challengeText)
            addProperty("name", session.name)
            session.profileId?.let { addProperty("id", it.toString()) }
        }
        // Proof of the account: the chat-signing certificate Mojang issues every Microsoft account, plus
        // the challenge signed with its key — checked by the Worker offline (Mojang refuses hasJoined
        // calls from Cloudflare). Without one (offline accounts), fall back to the server-style handshake,
        // which self-hosted and local Workers can still confirm with Mojang.
        val keys = runCatching { client.profileKeyPairManager.prepareKeyPair().get(10, TimeUnit.SECONDS).orElse(null) }.getOrNull()
        if (keys != null) {
            val data = keys.publicKey().data()
            val encoder = Base64.getEncoder()
            body.add("certificate", JsonObject().apply {
                addProperty("publicKey", encoder.encodeToString(data.key().encoded))
                addProperty("expiresAt", data.expiresAt().toEpochMilli())
                addProperty("keySignature", encoder.encodeToString(data.keySignature()))
            })
            val signer = Signature.getInstance("SHA256withRSA").apply {
                initSign(keys.privateKey())
                update(challengeText.toByteArray(Charsets.UTF_8))
            }
            body.addProperty("signature", encoder.encodeToString(signer.sign()))
        } else {
            runCatching { client.jukzSessionService.joinServer(session.profileId, session.accessToken, challenge.get("serverId").asString) }
                .onFailure { JukzMod.logger.info("jukz: cosmetics sign-in without a Mojang certificate or session ({})", it.message) }
        }
        val answer = call("/session", body, null)
        val token = answer.get("token").asString
        val me = get("/me", token)
        return Account.SignedIn(
            id = UUID.fromString(answer.get("id").asString),
            token = token,
            expiresAt = answer.get("expiresAt").asLong,
            picks = parseLoadout(me.getAsJsonObject("loadout")),
            owned = me.getAsJsonArray("owned").map { it.asString }.toSet(),
        )
    }

    private fun refreshCatalog() {
        if (catalogFetched) return
        worker.execute {
            runCatching {
                catalog = CosmeticCatalog.parse(send(request("/catalog").GET()))
                catalogFetched = true
            }.onFailure { JukzMod.logger.info("jukz: using the bundled cosmetics catalog ({})", it.message) }
        }
    }

    private fun flushLookups() {
        if (queued.isEmpty() || !enabled) return
        val batch = queued.take(100).also { queued.removeAll(it.toSet()) }
        val now = System.currentTimeMillis()
        // Mark them first so a failed lookup isn't retried every half second.
        batch.forEach { known[it] = Known(known[it]?.loadout ?: emptyMap(), now) }
        val answer = JsonParser.parseString(send(request("/players?ids=" + batch.joinToString(",")).GET())).asJsonObject
        val loadouts = answer.getAsJsonObject("loadouts") ?: JsonObject()
        batch.forEach { id -> known[id] = Known(parseLoadout(loadouts.getAsJsonObject(id.toString())), now) }
        // An item newer than our catalog: fetch the live one (once per session).
        val ids = loadouts.entrySet().flatMap { e -> e.value.asJsonObject.entrySet().map { it.value.asString } }
        if (ids.any { catalog.item(it) == null }) refreshCatalog()
    }

    private fun parseLoadout(json: JsonObject?): Map<Slot, String> =
        json?.entrySet()?.mapNotNull { (key, value) -> Slot.of(key)?.let { it to value.asString } }?.toMap() ?: emptyMap()

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
