package dev.jukz.world

import dev.jukz.JukzMod
import dev.jukz.core.model.WorldId
import dev.jukz.core.model.WorldKey
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The world's ownership key on disk (`jukz.key` in the save folder) and in memory. The file is part of
 * the save, so JGit snapshots — the live handoff and the cloud backup — carry it to the next host.
 * Guests get it in-game from the host ([dev.jukz.net.WorldAccessPayload]) and keep it under
 * `config/jukz-keys/`, so a player who has been in a world can later revive it from the cloud.
 *
 * The in-memory ring lets signing work after the world has closed (the withdraw and the cloud upload
 * run during/after shutdown), and remembers worlds the rendezvous refused because this copy holds a
 * different key than the one bound there.
 */
object WorldKeyStore {
    private const val FILE = "jukz.key"

    private val ring = ConcurrentHashMap<WorldId, WorldKey>()
    private val refused = ConcurrentHashMap.newKeySet<WorldId>()

    /**
     * Read the save's key; a save without one takes the key this player got as a guest, or — for a world
     * nobody has keyed yet — a new one. Remember it.
     */
    fun loadOrCreate(saveRoot: Path, worldId: WorldId): WorldKey? = runCatching {
        val file = saveRoot.resolve(FILE)
        val key = if (Files.exists(file)) {
            WorldKey.decode(Files.readString(file))
        } else {
            val fromGuest = guestKey(worldId)
            (fromGuest ?: WorldKey.generate()).also {
                Files.writeString(file, it.encode())
                if (fromGuest == null) JukzMod.logger.info("jukz: created the ownership key for {}", worldId.shortCode())
            }
        }
        ring[worldId] = key
        refused.remove(worldId)
        key
    }.onFailure { JukzMod.logger.warn("jukz: could not load the world key ({}); announcing unsigned", it.message) }.getOrNull()

    /** The key for [worldId]: one already loaded, or one this player received as a guest. */
    fun keyFor(worldId: WorldId): WorldKey? = ring[worldId] ?: guestKey(worldId)?.also { ring[worldId] = it }

    /** Load the key a save already has (no creating), e.g. before asking the cloud for its backup. */
    fun loadExisting(saveRoot: Path, worldId: WorldId) {
        val file = saveRoot.resolve(FILE)
        if (Files.exists(file)) runCatching { ring[worldId] = WorldKey.decode(Files.readString(file)) }
    }

    /** Keep the key the host gave us in-game, so this player can revive the world from the cloud later. */
    fun rememberFromHost(worldId: WorldId, text: String) {
        runCatching {
            val key = WorldKey.decode(text)
            ring[worldId] = key
            Files.createDirectories(guestDir())
            Files.writeString(guestDir().resolve("${worldId.uuid}.key"), text)
        }.onFailure { JukzMod.logger.warn("jukz: could not keep the world key from the host ({})", it.message) }
    }

    private fun guestKey(worldId: WorldId): WorldKey? = runCatching {
        val file = guestDir().resolve("${worldId.uuid}.key")
        if (Files.exists(file)) WorldKey.decode(Files.readString(file)) else null
    }.getOrNull()

    private fun guestDir(): Path = FabricLoader.getInstance().configDir.resolve("jukz-keys")

    /** Remember a key that arrived inside a snapshot this install won't apply (see JoinCoordinator). */
    fun remember(worldId: WorldId, encoded: ByteArray) {
        runCatching { ring[worldId] = WorldKey.decode(String(encoded, Charsets.UTF_8)) }
            .onFailure { JukzMod.logger.warn("jukz: the snapshot's world key is unreadable ({})", it.message) }
    }

    const val FILE_NAME = FILE

    /** The rendezvous refused this copy's key (another key owns the world there). */
    fun markRefused(worldId: WorldId) {
        refused.add(worldId)
    }

    fun isRefused(worldId: WorldId): Boolean = worldId in refused
}
