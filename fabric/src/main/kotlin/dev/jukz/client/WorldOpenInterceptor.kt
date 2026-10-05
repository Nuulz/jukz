package dev.jukz.client

import dev.jukz.compat.currentScreen
import dev.jukz.compat.openScreen
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtAccounter
import dev.jukz.client.gui.VersionScreen
import dev.jukz.core.model.VersionFit
import dev.jukz.core.model.GameVersion
import dev.jukz.compat.string
import dev.jukz.compat.int
import dev.jukz.compat.compound
import dev.jukz.compat.currentGame
import dev.jukz.JukzMod
import dev.jukz.client.gui.SearchingHostScreen
import dev.jukz.core.model.WorldId
import dev.jukz.discovery.Discovery
import dev.jukz.sync.R2SnapshotStore
import dev.jukz.world.WorldKeyStore
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import net.minecraft.client.Minecraft

/**
 * Makes "the world lives in one place" the default behaviour of opening a singleplayer world. Driven
 * from a mixin at the head of `WorldOpenFlows.openWorld`: before the local server boots, the
 * world's persisted jukz UUID (read from its `jukz.dat` sidecar without starting the world) is looked
 * up in discovery. If a live host is announced, the local boot is cancelled and the player is joined
 * to that host as a guest; otherwise the world opens locally as usual. No button — joining is an
 * intrinsic property of opening the world.
 *
 * Discovery comes from the shared [Discovery] registry: LAN multicast finds same-network hosts and
 * the rendezvous adapter finds internet-wide ones, so the lookup is real cross-machine detection —
 * a live host elsewhere cancels the local boot and joins as a guest, otherwise the world opens
 * locally (then auto-hosts via [HostCoordinator]).
 */
object WorldOpenInterceptor {

    // Guards the re-entrant local boot we trigger ourselves (openLocally) from being intercepted
    // again. Only ever touched on the render thread, so a plain volatile flag is enough.
    @Volatile
    private var bypass = false

    /**
     * Head hook of `WorldOpenFlows.openWorld`. Returns true to cancel the local boot because jukz
     * is handling the open (consulting discovery / joining a host); false to let the world boot
     * locally. [onCancel] is the vanilla "loading was aborted" callback, threaded through to the
     * re-entrant local boot.
     */
    fun shouldIntercept(levelName: String, onCancel: Runnable): Boolean {
        if (bypass) {
            bypass = false
            return false
        }
        val info = readSidecar(levelName) ?: return false // not a jukz world -> open normally
        beginConsult(WorldId.of(info.worldId), levelName, onCancel)
        return true
    }

    private fun readSidecar(levelName: String): WorldIdSidecar.Info? = runCatching {
        val saveRoot = Minecraft.getInstance().levelSource.baseDir.resolve(levelName)
        WorldIdSidecar.read(saveRoot)
    }.getOrNull()

    private fun beginConsult(worldId: WorldId, levelName: String, onCancel: Runnable) {
        val client = Minecraft.getInstance()
        val parent = client.currentScreen // the world-select screen, to fall back to
        val shortCode = worldId.shortCode()
        client.openScreen(SearchingHostScreen(shortCode) { openLocally(levelName, onCancel) })

        Thread {
            val live = try {
                runBlocking { Discovery.registry.lookup(worldId) }
            } catch (e: Throwable) {
                null
            }
            if (live != null && VersionFit.of(currentGame, live.game) != VersionFit.SAME) {
                // Someone hosts it on another Minecraft version: the game couldn't connect, and opening the
                // local copy would split the world. Say so instead.
                client.execute { client.openScreen(VersionScreen.hostOnOtherVersion(live.game ?: GameVersion.LEGACY) { client.openScreen(parent) }) }
                return@Thread
            }
            if (live != null) {
                client.execute {
                    JukzMod.logger.info("jukz: {} is hosted live — joining instead of opening locally", shortCode)
                    JoinCoordinator.start(worldId, shortCode, parent)
                }
                return@Thread
            }
            // No live host. Before booting the local (possibly stale) copy, check R2 for a ghost that is
            // strictly newer than what we hold on disk — if so, pull it and take over, so opening the
            // world from the singleplayer list gets "the world lives in one place" too, not only the
            // join-by-code flow. Probe off the render thread; the generation lives in the head object.
            val localGen = runCatching {
                WorldIdSidecar.generation(Minecraft.getInstance().levelSource.baseDir.resolve(levelName))
            }.getOrNull() ?: -1L
            // Our copy's key signs the request (a keyed world's backup only goes to key holders).
            runCatching { WorldKeyStore.loadExisting(client.levelSource.baseDir.resolve(levelName), worldId) }
            val ghost = runCatching { R2SnapshotStore.ghostSnapshot(worldId) }.getOrNull()
            val head = ghost?.let { runCatching { R2SnapshotStore.ghostHead(it.headUrl) }.getOrNull() }
            client.execute {
                if (ghost != null && head != null && head.generation > localGen) {
                    JukzMod.logger.info(
                        "jukz: {} has a newer cloud copy (gen {} > local {}) — loading it",
                        shortCode, head.generation, localGen,
                    )
                    val takeOver = { JoinCoordinator.takeOverGhost(worldId, shortCode, parent, ghost, head.commit) }
                    val saved = head.game ?: GameVersion.LEGACY
                    when (VersionFit.of(currentGame, head.game)) {
                        VersionFit.SAME -> takeOver()
                        VersionFit.UPGRADE -> client.openScreen(VersionScreen.upgrade(saved, { client.openScreen(parent) }) { takeOver() })
                        VersionFit.TOO_NEW -> client.openScreen(VersionScreen.tooNew(saved) { client.openScreen(parent) })
                    }
                } else {
                    // Our own copy: one saved on an older version is upgraded by opening it, so ask first.
                    val saved = localSaveVersion(levelName)
                    if (saved != null && VersionFit.of(currentGame, saved) == VersionFit.UPGRADE) {
                        client.openScreen(VersionScreen.upgrade(saved, { client.openScreen(parent) }) { openLocally(levelName, onCancel) })
                    } else {
                        openLocally(levelName, onCancel)
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "jukz-open-consult"
        }.start()
    }

    /** The version a save was last played on, from its level.dat (Data.DataVersion and Data.Version.Name). */
    private fun localSaveVersion(levelName: String): GameVersion? = runCatching {
        val levelDat = Minecraft.getInstance().levelSource.baseDir.resolve(levelName).resolve("level.dat")
        val data = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap()).compound("Data")
        val dataVersion = data.int("DataVersion") ?: return null
        GameVersion(data.compound("Version").string("Name") ?: "an older version", dataVersion)
    }.getOrNull()

    /** Resume the vanilla local boot, bypassing this interceptor for the re-entrant call. */
    private fun openLocally(levelName: String, onCancel: Runnable) {
        bypass = true
        Minecraft.getInstance().createWorldOpenFlows().openWorld(levelName, onCancel)
    }

    /**
     * Open a world locally, *bypassing* the discovery consult, so it boots and auto-hosts even if the
     * old host is still inside its snapshot-offer window. Used by the handoff takeover (F4-B): the guest
     * has already pulled the latest save, so the auto-host generation bump fences past the old host.
     */
    fun openLocallyBypassingDiscovery(levelName: String) {
        openLocally(levelName) {
            JukzMod.logger.warn("jukz: takeover boot of {} was cancelled", levelName)
        }
    }
}
