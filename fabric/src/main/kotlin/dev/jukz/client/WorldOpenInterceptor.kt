package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.SearchingHostScreen
import dev.jukz.core.model.WorldId
import dev.jukz.discovery.Discovery
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient

/**
 * Makes "the world lives in one place" the default behaviour of opening a singleplayer world. Driven
 * from a mixin at the head of `IntegratedServerLoader.start`: before the local server boots, the
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
     * Head hook of `IntegratedServerLoader.start`. Returns true to cancel the local boot because jukz
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
        val saveRoot = MinecraftClient.getInstance().levelStorage.savesDirectory.resolve(levelName)
        WorldIdSidecar.read(saveRoot)
    }.getOrNull()

    private fun beginConsult(worldId: WorldId, levelName: String, onCancel: Runnable) {
        val client = MinecraftClient.getInstance()
        val parent = client.currentScreen // the world-select screen, to fall back to
        val shortCode = worldId.shortCode()
        client.setScreen(SearchingHostScreen(shortCode) { openLocally(levelName, onCancel) })

        Thread {
            val live = try {
                runBlocking { Discovery.registry.lookup(worldId) }
            } catch (e: Throwable) {
                null
            }
            client.execute {
                if (live != null) {
                    JukzMod.logger.info("jukz: {} is hosted live — joining instead of opening locally", shortCode)
                    JoinCoordinator.start(worldId, shortCode, parent)
                } else {
                    openLocally(levelName, onCancel)
                }
            }
        }.apply {
            isDaemon = true
            name = "jukz-open-consult"
        }.start()
    }

    /** Resume the vanilla local boot, bypassing this interceptor for the re-entrant call. */
    private fun openLocally(levelName: String, onCancel: Runnable) {
        bypass = true
        MinecraftClient.getInstance().createIntegratedServerLoader().start(levelName, onCancel)
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
