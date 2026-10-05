package dev.jukz.client

import dev.jukz.core.model.VersionFit
import dev.jukz.core.model.GameVersion
import dev.jukz.compat.currentGame
import dev.jukz.compat.string
import dev.jukz.compat.compound
import dev.jukz.compat.toast
import com.google.gson.JsonParser
import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.model.WorldId
import dev.jukz.cosmetics.Cosmetics
import dev.jukz.sync.JGitWorldSync
import dev.jukz.sync.R2SnapshotStore
import dev.jukz.world.WorldSaveLocator
import kotlinx.coroutines.runBlocking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import org.eclipse.jgit.lib.ObjectId
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Your worlds follow your Minecraft account. When a player signed in with a Microsoft account backs a
 * world up to the cloud, the rendezvous remembers it on their account ([uploadHeaders]); on any other PC
 * where they sign in, [bringNew] downloads the worlds that PC doesn't have yet into `saves/` — with
 * the world key inside, so they host it like on the first PC. Offline accounts can't sign in, so this is
 * premium-only by construction.
 *
 * A world brought once and later deleted here is not brought again ([Seen]); the "My cloud" screen can
 * still bring it on purpose, or forget it from the account. Worlds already here are left to the normal
 * open path, which pulls a newer cloud copy by itself.
 */
object CloudWorlds {
    /** An account world; [game] is the Minecraft version it was backed up on (null: 1.21.1, older mods). */
    class World(val worldId: UUID, val name: String, val generation: Long, val updated: Long, val game: GameVersion?) {
        val fit: VersionFit get() = VersionFit.of(currentGame, game)
    }

    enum class State { HERE, AWAY, BRINGING, FAILED }

    @Volatile var worlds: List<World>? = null
        private set
    private val states = java.util.concurrent.ConcurrentHashMap<UUID, State>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "jukz-cloud-worlds").apply { isDaemon = true } }
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
    @Volatile private var autoChecked = false

    fun state(world: World): State = states[world.worldId] ?: if (isHere(world.worldId)) State.HERE else State.AWAY

    /** Headers that tie a cloud upload to the signed-in account: its session and the world's name. */
    fun uploadHeaders(worldId: WorldId): List<Pair<String, String>> {
        val token = Cosmetics.sessionToken() ?: return emptyList()
        val name = runCatching { localName(worldId.uuid) }.getOrNull() ?: "World"
        return listOf(
            "x-jukz-cosmetics-token" to token,
            "x-jukz-level-name" to URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20"),
            "x-jukz-game" to "${currentGame.name};${currentGame.dataVersion}", // the account list shows it
        )
    }

    /**
     * Once per launch, when signed in: bring every account world this PC has never had. Called from a
     * client tick, so it waits for the sign-in that joining a world or the title screen kicks off.
     */
    fun bringNewOnce() {
        if (autoChecked || Cosmetics.sessionToken() == null) return
        autoChecked = true
        worker.execute {
            val list = runCatching { refreshNow() }.getOrElse {
                JukzMod.logger.info("jukz: cloud worlds unavailable ({})", it.message)
                return@execute
            }
            val seen = Seen.load()
            // Only worlds of this very version come on their own: a newer one can't open here, and an older
            // one would be upgraded (and lost to friends on its version) the moment it's opened.
            val missing = list.filter { !isHere(it.worldId) && it.worldId !in seen && it.fit == VersionFit.SAME }
            if (missing.isEmpty()) return@execute
            toast("Your worlds from another PC", "Bringing ${missing.size} world${if (missing.size == 1) "" else "s"} from your cloud…")
            val brought = missing.filter { bringNow(it) }
            if (brought.isNotEmpty()) {
                toast("Worlds ready", brought.joinToString(", ") { it.name }.take(80) + " — in Singleplayer")
            }
        }
    }

    /** Re-read the account's list in the background; [done] runs on the client thread. */
    fun refresh(done: () -> Unit = {}) {
        worker.execute {
            runCatching { refreshNow() }.onFailure { JukzMod.logger.info("jukz: cloud worlds unavailable ({})", it.message) }
            Minecraft.getInstance().execute(done)
        }
    }

    /** Bring one world on purpose (the "My cloud" screen), even if it was deleted here before. */
    fun bring(world: World, done: () -> Unit = {}) {
        states[world.worldId] = State.BRINGING
        worker.execute {
            bringNow(world)
            Minecraft.getInstance().execute(done)
        }
    }

    /** Remove a world from the account's list (its cloud backup and any copies stay). */
    fun forget(world: World, done: () -> Unit = {}) {
        worker.execute {
            runCatching { post("/v1/account/worlds/${world.worldId}/forget") }
            runCatching { refreshNow() }
            Minecraft.getInstance().execute(done)
        }
    }

    // ---- the account screen ------------------------------------------------------------------

    /** What the account screen shows for a signed-in player (GET /v1/account/summary). */
    class Summary(
        val name: String,
        val uploadsToday: Int,
        val uploadsPerDay: Int,
        val maxBackupMb: Int,
        val keepDays: Int,
        val ownedCosmetics: Int,
        val creationsPublished: Int?,
        val creationsPending: Int?,
    )

    @Volatile var summary: Summary? = null
        private set

    /** A jukz world in this PC's saves/, by folder, with the name the game shows. */
    class LocalWorld(val folder: String, val name: String, val worldId: UUID, val generation: Long)

    enum class Upload { UPLOADING, DONE, FAILED }

    /** Per local world: the last "upload to my cloud" attempt, and its message for the screen. */
    val uploads = java.util.concurrent.ConcurrentHashMap<UUID, Pair<Upload, String>>()

    /** Refresh the summary and the cloud world list together; [done] runs on the client thread. */
    fun refreshAccount(done: () -> Unit = {}) {
        worker.execute {
            refreshAccountNow()
            Minecraft.getInstance().execute(done)
        }
    }

    private fun refreshAccountNow() {
            runCatching {
                val o = JsonParser.parseString(send(request("/v1/account/summary").GET())).asJsonObject
                val limits = o.getAsJsonObject("limits").getAsJsonObject(o.get("tier").asString)
                val creator = o.get("creator")?.takeIf { !it.isJsonNull }?.asJsonObject
                summary = Summary(
                    name = o.get("name").asString,
                    uploadsToday = o.get("uploadsToday").asInt,
                    uploadsPerDay = limits.get("uploadsPerDay").asInt,
                    maxBackupMb = (limits.get("maxSnapshotBytes").asLong / 1_048_576).toInt(),
                    keepDays = limits.get("keepDays").asInt,
                    ownedCosmetics = o.get("owned").asInt,
                    creationsPublished = creator?.get("published")?.asInt,
                    creationsPending = creator?.get("pending")?.asInt,
                )
                worlds = o.getAsJsonArray("worlds").map { worldFromJson(it.asJsonObject) }
            }.onFailure { JukzMod.logger.info("jukz: account summary unavailable ({})", it.message) }
    }

    /** The jukz worlds in this PC's saves/ (those with a world id), newest folder first. */
    fun localWorlds(): List<LocalWorld> = runCatching {
        Files.list(savesDir()).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList().mapNotNull { dir ->
                val info = runCatching { dev.jukz.world.WorldIdSidecar.read(dir) }.getOrNull() ?: return@mapNotNull null
                LocalWorld(dir.fileName.toString(), levelName(dir) ?: dir.fileName.toString(), info.worldId,
                    dev.jukz.world.WorldIdSidecar.generation(dir) ?: info.generation)
            }.sortedByDescending { runCatching { Files.getLastModifiedTime(savesDir().resolve(it.folder)).toMillis() }.getOrDefault(0L) }
        }
    }.getOrDefault(emptyList())

    /**
     * Back a world of this PC up to the cloud, on this account — what closing it alone does, from the
     * menu. Not for the world that is open right now (the game is still writing it).
     */
    fun upload(world: LocalWorld, done: () -> Unit = {}) {
        uploads[world.worldId] = Upload.UPLOADING to "packing…"
        worker.execute {
            val result = runCatching {
                val saveDir = savesDir().resolve(world.folder)
                val worldId = WorldId(world.worldId)
                dev.jukz.world.WorldKeyStore.loadOrCreate(saveDir, worldId) // uploads are signed with the world key
                val pack = dev.jukz.sync.SnapshotPack.build(saveDir, JGitWorldSync(), dev.jukz.sync.SnapshotCodec.Level.SMALL)
                    ?: error("couldn't pack the world")
                uploads[world.worldId] = Upload.UPLOADING to "uploading %.1f MB…".format(pack.bytes.size / 1_048_576.0)
                R2SnapshotStore.uploadGhost(worldId, world.generation, pack.bytes, pack.head) { sent, total ->
                    if (total > 0) uploads[world.worldId] = Upload.UPLOADING to "uploading %.1f / %.1f MB…".format(sent / 1_048_576.0, total / 1_048_576.0)
                }
            }.getOrElse { R2SnapshotStore.UploadResult.Refused(it.message ?: "failed") }
            uploads[world.worldId] = when (result) {
                R2SnapshotStore.UploadResult.Done -> Upload.DONE to "in your cloud"
                is R2SnapshotStore.UploadResult.Refused -> Upload.FAILED to result.message
                R2SnapshotStore.UploadResult.Retry -> Upload.FAILED to "couldn't reach the server; try again"
            }
            if (result == R2SnapshotStore.UploadResult.Done) {
                Seen.add(world.worldId) // it's here already: never "bring" it back
                refreshAccountNow() // the list and today's count
            }
            Minecraft.getInstance().execute(done)
        }
    }

    private fun levelName(dir: Path): String? = runCatching {
        val root = net.minecraft.nbt.NbtIo.readCompressed(dir.resolve("level.dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap())
        root.compound("Data").string("LevelName")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---- work (worker thread) -----------------------------------------------------------------

    private fun refreshNow(): List<World> {
        val body = JsonParser.parseString(send(request("/v1/account/worlds").GET())).asJsonObject
        val list = body.getAsJsonArray("worlds").map { worldFromJson(it.asJsonObject) }
        worlds = list
        return list
    }

    private fun worldFromJson(o: com.google.gson.JsonObject): World {
        val game = o.get("game")?.takeIf { it.isJsonObject }?.asJsonObject?.let { g ->
            runCatching { GameVersion(g.get("name").asString, g.get("dataVersion").asInt) }.getOrNull()
        }
        return World(UUID.fromString(o.get("worldId").asString), o.get("name").asString, o.get("generation").asLong, o.get("updated").asLong, game)
    }

    private fun bringNow(world: World): Boolean {
        states[world.worldId] = State.BRINGING
        val ok = runCatching {
            val urls = JsonParser.parseString(post("/v1/account/worlds/${world.worldId}/download")).asJsonObject
            val head = R2SnapshotStore.ghostHead(urls.get("headUrl").asString) ?: error("no backup in the cloud")
            // The backup itself says which version saved it; a newer one couldn't be opened here.
            if (VersionFit.of(currentGame, head.game) == VersionFit.TOO_NEW) error("it was saved on Minecraft ${head.game?.name}")
            val pack = R2SnapshotStore.downloadToTemp(urls.get("packUrl").asString) { _, _ -> } ?: error("download failed")
            try {
                val saveDir = freeSaveDir(world.name)
                val applied = runBlocking {
                    JGitWorldSync().applySnapshot(saveDir, JGitWorldSync.Downloaded(pack, ObjectId.fromString(head.commit)), WorldId(world.worldId), head.generation)
                }
                if (!applied) {
                    runCatching { deleteRecursively(saveDir) }
                    error("couldn't unpack it")
                }
                JukzMod.logger.info("jukz: brought cloud world {} into {}", world.name, saveDir.fileName)
            } finally {
                Files.deleteIfExists(pack)
            }
        }.onFailure { JukzMod.logger.warn("jukz: couldn't bring cloud world {} ({})", world.name, it.message) }.isSuccess
        states[world.worldId] = if (ok) State.HERE else State.FAILED
        if (ok) Seen.add(world.worldId)
        else toast("Couldn't bring ${world.name}", "Try again from your jukz account (the person icon).")
        return ok
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun savesDir(): Path = Minecraft.getInstance().levelSource.baseDir

    private fun isHere(worldId: UUID): Boolean = WorldSaveLocator.findLevelName(savesDir(), worldId) != null

    private fun localName(worldId: UUID): String? = WorldSaveLocator.findLevelName(savesDir(), worldId)

    /** A new folder in saves/ named after the world ("Name", "Name (2)", …), safe for any OS. */
    private fun freeSaveDir(name: String): Path {
        val base = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifEmpty { "World" }.take(48)
        var candidate = savesDir().resolve(base)
        var n = 2
        while (Files.exists(candidate)) candidate = savesDir().resolve("$base ($n)").also { n++ }
        return candidate
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun toast(title: String, body: String) {
        val client = Minecraft.getInstance()
        client.execute { client.toast(Component.literal(title), Component.literal(body)) }
    }

    private fun request(path: String): HttpRequest.Builder {
        val token = Cosmetics.sessionToken() ?: error("not signed in")
        val base = JukzConfig.rendezvousUrl ?: error("no rendezvous")
        val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("x-jukz-cosmetics-token", token)
        JukzConfig.rendezvousAuthToken?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun post(path: String): String = send(request(path).POST(HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type", "application/json"))

    private fun send(builder: HttpRequest.Builder): String {
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) return response.body()
        val message = runCatching { JsonParser.parseString(response.body()).asJsonObject.get("message").asString }.getOrNull()
        error(message ?: "HTTP ${response.statusCode()}")
    }

    /** Worlds this PC already received from the cloud (config/jukz-cloud-worlds.properties). */
    private object Seen {
        private val file get() = net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("jukz-cloud-worlds.properties")

        fun load(): Set<UUID> = runCatching {
            val props = Properties()
            if (Files.exists(file)) Files.newBufferedReader(file).use(props::load)
            props.stringPropertyNames().mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        }.getOrDefault(emptySet())

        @Synchronized
        fun add(worldId: UUID) {
            runCatching {
                val props = Properties()
                if (Files.exists(file)) Files.newBufferedReader(file).use(props::load)
                props.setProperty(worldId.toString(), System.currentTimeMillis().toString())
                Files.newBufferedWriter(file).use { props.store(it, "jukz: worlds brought from your cloud (not brought again if deleted)") }
            }
        }
    }

}
