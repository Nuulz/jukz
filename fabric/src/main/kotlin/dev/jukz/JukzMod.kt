package dev.jukz

import dev.jukz.runtime.HostSession
import dev.jukz.world.WorldIdSidecar
import dev.jukz.world.WorldIdState
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents
import net.minecraft.world.World
import org.slf4j.LoggerFactory

/**
 * Common entrypoint. Wires the integrated-server lifecycle (which fires for singleplayer too):
 *  - ServerWorldEvents.LOAD (overworld) -> ensure the world UUID + generation exist and mirror them
 *    to the pre-start sidecar.
 *  - SERVER_STOPPING -> withdraw whatever the player shared (covers exit-to-menu AND quit, unlike
 *    CLIENT_STOPPING which would leak a stale host record).
 *
 * Sharing is explicit (the client "Play together" button drives `ShareCoordinator`), so there is no
 * host-on-start hook here — a singleplayer world stays private until the player opens it.
 */
object JukzMod : ModInitializer {
    const val MOD_ID = "jukz"
    val logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        ServerWorldEvents.LOAD.register { server, world ->
            if (world.registryKey == World.OVERWORLD) {
                val state = WorldIdState.get(world)
                WorldIdSidecar.write(server, state)
                logger.info("jukz world {} (generation {})", state.worldId, state.generation)
            }
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { HostSession.onServerStopping() }

        logger.info("jukz initialized")
    }
}
