package dev.jukz.discovery

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.core.discovery.PublishResult
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [WorldRegistry] backed by the jukz rendezvous server (`rendezvous/` in this repo, Rust + Axum) —
 * the internet-wide discovery path. Speaks the JSON `/v1` contract; the server replicates the
 * ClaimToken CAS semantics of [dev.jukz.core.discovery.InMemoryWorldRegistry], enforces the lease
 * TTL, and appends the announcer's observed public address to the record's endpoint list (which is
 * what makes a record dialable across NATs without client-side STUN).
 *
 * Failure policy (deliberate, see the WorldSync decisions):
 *  - lookup: a network error reads as "nobody is hosting" — the backend being down must never
 *    block opening a world.
 *  - announce/heartbeat: network errors are absorbed optimistically (we keep acting as host) and
 *    every later heartbeat retries; when the server answers again with "unknown" (lease expired or
 *    server restarted) the heartbeat transparently re-announces. Only a definitive "superseded"
 *    verdict — a strictly newer token holds the world — reports the lease as lost.
 */
class RendezvousWorldRegistry(
    baseUrl: String,
    private val authToken: String? = null,
    private val lookupTimeout: Duration = Duration.ofSeconds(4),
    private val writeTimeout: Duration = Duration.ofSeconds(10),
) : WorldRegistry {

    private val base = baseUrl.trimEnd('/')
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile private var serverTtlMs: Long? = null
    private val unreachableLogged = AtomicBoolean(false)

    override fun leaseTtlMs(): Long? = serverTtlMs

    override suspend fun publishIfNewer(record: WorldRecord): PublishResult = withContext(Dispatchers.IO) {
        val response = try {
            send(post("/v1/announce", announceBody(record)), writeTimeout)
        } catch (e: Exception) {
            logUnreachable("announce", e)
            // Optimistic: keep hosting; the heartbeat loop keeps retrying the announce.
            return@withContext PublishResult.Published(record)
        }
        when (response.statusCode()) {
            200 -> {
                val body = JsonParser.parseString(response.body()).asJsonObject
                body.get("ttlMs")?.asLong?.let { serverTtlMs = it }
                logReachable()
                // The server's copy of the record carries the observed public endpoint.
                PublishResult.Published(recordFromJson(body.getAsJsonObject("record")))
            }
            409 -> {
                val body = JsonParser.parseString(response.body()).asJsonObject
                PublishResult.Rejected(recordFromJson(body.getAsJsonObject("current")))
            }
            else -> {
                JukzMod.logger.warn("jukz: rendezvous announce returned HTTP {}", response.statusCode())
                PublishResult.Published(record) // treat like unreachable: never block local play
            }
        }
    }

    override suspend fun heartbeat(record: WorldRecord): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("worldId", record.worldId.uuid.toString())
            add("token", tokenToJson(record.token))
            addProperty("heartbeatSeq", record.heartbeatSeq)
        }
        val response = try {
            send(post("/v1/heartbeat", body), writeTimeout)
        } catch (e: Exception) {
            logUnreachable("heartbeat", e)
            return@withContext true // optimistic; retried on the next beat
        }
        when (response.statusCode()) {
            200 -> {
                JsonParser.parseString(response.body()).asJsonObject.get("ttlMs")?.asLong?.let { serverTtlMs = it }
                logReachable()
                true
            }
            409 -> {
                val verdict = JsonParser.parseString(response.body()).asJsonObject
                when (verdict.get("status")?.asString) {
                    // Lease gone but unowned (expired / server restarted): re-announce in place.
                    "unknown" -> {
                        JukzMod.logger.info("jukz: rendezvous lost our lease; re-announcing")
                        publishIfNewer(record) is PublishResult.Published
                    }
                    else -> false // superseded: a strictly newer host owns the world
                }
            }
            else -> {
                JukzMod.logger.warn("jukz: rendezvous heartbeat returned HTTP {}", response.statusCode())
                true
            }
        }
    }

    override suspend fun lookup(worldId: WorldId): WorldRecord? = withContext(Dispatchers.IO) {
        val request = request("/v1/worlds/${worldId.uuid}").GET().timeout(lookupTimeout).build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            logUnreachable("lookup", e)
            return@withContext null // backend down must never block opening a world
        }
        when (response.statusCode()) {
            200 -> {
                logReachable()
                runCatching { recordFromJson(JsonParser.parseString(response.body()).asJsonObject) }
                    .onFailure { JukzMod.logger.warn("jukz: malformed rendezvous record: {}", it.message) }
                    .getOrNull()
            }
            404 -> {
                logReachable()
                null
            }
            else -> {
                JukzMod.logger.warn("jukz: rendezvous lookup returned HTTP {}", response.statusCode())
                null
            }
        }
    }

    override suspend fun withdraw(worldId: WorldId, token: ClaimToken): Unit = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("worldId", worldId.uuid.toString())
            add("token", tokenToJson(token))
        }
        runCatching { send(post("/v1/withdraw", body), writeTimeout) }
            .onFailure { logUnreachable("withdraw", it) } // best-effort; the lease TTL covers us
        Unit
    }

    // ---- HTTP plumbing -------------------------------------------------------------------

    private fun request(path: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun post(path: String, body: JsonObject): HttpRequest.Builder =
        request(path).POST(HttpRequest.BodyPublishers.ofString(body.toString()))

    private fun send(builder: HttpRequest.Builder, timeout: Duration): HttpResponse<String> =
        http.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofString())

    /** Warn once per outage, not once per beat; an info line marks recovery. */
    private fun logUnreachable(op: String, e: Throwable) {
        if (unreachableLogged.compareAndSet(false, true)) {
            JukzMod.logger.warn("jukz: rendezvous server unreachable on {} ({}); continuing without it", op, e.message)
        }
    }

    private fun logReachable() {
        if (unreachableLogged.compareAndSet(true, false)) {
            JukzMod.logger.info("jukz: rendezvous server reachable again")
        }
    }

    // ---- JSON mapping (mirrors rendezvous/src/main.rs) -----------------------------------

    private fun announceBody(record: WorldRecord): JsonObject = JsonObject().apply {
        addProperty("worldId", record.worldId.uuid.toString())
        add("token", tokenToJson(record.token))
        add("endpoints", endpointsToJson(record.endpoints))
        addProperty("heartbeatSeq", record.heartbeatSeq)
    }

    private fun tokenToJson(token: ClaimToken): JsonObject = JsonObject().apply {
        addProperty("generation", token.hostGeneration)
        addProperty("claimEpochMillis", token.claimEpochMillis)
        addProperty("nodeId", token.nodeId.toHex())
    }

    private fun endpointsToJson(endpoints: List<Endpoint>): JsonArray = JsonArray().apply {
        for (endpoint in endpoints) {
            add(JsonObject().apply {
                addProperty("host", endpoint.host)
                addProperty("port", endpoint.port)
            })
        }
    }

    private fun recordFromJson(json: JsonObject): WorldRecord {
        val token = json.getAsJsonObject("token")
        return WorldRecord(
            worldId = WorldId.of(UUID.fromString(json.get("worldId").asString)),
            token = ClaimToken(
                hostGeneration = token.get("generation").asLong,
                claimEpochMillis = token.get("claimEpochMillis").asLong,
                nodeId = NodeId.fromHex(token.get("nodeId").asString),
            ),
            endpoints = json.getAsJsonArray("endpoints").map {
                val o = it.asJsonObject
                Endpoint(o.get("host").asString, o.get("port").asInt)
            },
            heartbeatSeq = json.get("heartbeatSeq").asLong,
        )
    }
}
