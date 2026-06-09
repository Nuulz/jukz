package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.SearchingHostScreen
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.SystemClock
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
 * Discovery defaults to the loopback-safe [InMemoryWorldRegistry], so today the lookup always comes
 * back empty and every world opens locally. The live DHT registry drops in behind [WorldRegistry] and
 * turns this into real cross-machine detection without any change here.
 */
object WorldOpenInterceptor {

    private val registry: WorldRegistry = InMemoryWorldRegistry(SystemClock)

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
                runBlocking { registry.lookup(worldId) }
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
}
