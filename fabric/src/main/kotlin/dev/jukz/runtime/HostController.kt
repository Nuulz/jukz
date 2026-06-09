package dev.jukz.runtime

import dev.jukz.JukzMod
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.util.SystemClock
import net.minecraft.server.MinecraftServer

/**
 * Bridges the Minecraft server lifecycle to the jukz discovery/election core. The deterministic
 * pieces (registry, election) are real and live behind interfaces; the live transport (DHT publish,
 * NAT traversal, world sync) is flagged and swaps in without changing this class.
 */
class HostController(
    @Suppress("unused") private val registry: WorldRegistry = InMemoryWorldRegistry(SystemClock),
) {
    fun onServerStarted(server: MinecraftServer) {
        // TODO(live-network): server.openToLan(...) to bind a port, run HostElection over the DHT,
        //  publish {worldId, token, endpoint}, and start the heartbeat loop. Needs NAT transport.
        JukzMod.logger.info("jukz: server started; host advertisement pending live transport")
    }

    fun onServerStopping(server: MinecraftServer) {
        // TODO(live-network): increment generation, WorldSync.commit(save), withdraw the DHT record.
        JukzMod.logger.info("jukz: server stopping; host withdrawal pending live transport")
    }
}
