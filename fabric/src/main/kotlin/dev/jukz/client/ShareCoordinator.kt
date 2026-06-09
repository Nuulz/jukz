package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.HostErrorScreen
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.client.gui.OpeningWorldScreen
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.host.HostController
import dev.jukz.core.host.HostResult
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.util.SystemClock
import dev.jukz.runtime.HostSession
import dev.jukz.transport.LocalEndpointResolver
import dev.jukz.world.WorldIdState
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.server.integrated.IntegratedServer
import java.util.concurrent.CompletableFuture

/**
 * Client driver for "Play together": opens the current singleplayer world to the network via jukz
 * and shows the copyable share code. Idempotent — once hosting, it just reopens the code screen.
 * The heavy work (open the port + publish) runs off the render thread behind an [OpeningWorldScreen],
 * and every [HostResult] maps to a concrete screen so a failure never reads as a freeze.
 *
 * Discovery defaults to the loopback-safe [InMemoryWorldRegistry] and the endpoint to the LAN-only
 * [LocalEndpointResolver]; the live DHT registry and the public [dev.jukz.transport
 * .StunEndpointResolver] drop in here behind the same interfaces. With the in-memory registry the
 * published record is real but only locally visible — actual cross-country reach arrives with those.
 */
object ShareCoordinator {

    fun share(parent: Screen?) {
        val client = MinecraftClient.getInstance()
        val server = client.server ?: return // only the local host of a singleplayer world can share

        // Already hosting this session: skip re-opening, just show the world info.
        if (HostSession.isHosting) {
            client.setScreen(HostInfoScreen(parent))
            return
        }

        client.setScreen(OpeningWorldScreen())
        Thread {
            val result = try {
                openAndHost(server)
            } catch (e: Throwable) {
                HostResult.Failed(e.message ?: e.toString())
            }
            client.execute { applyResult(client, result, parent) }
        }.apply {
            isDaemon = true
            name = "jukz-host"
        }.start()
    }

    private fun openAndHost(server: IntegratedServer): HostResult {
        val (worldId, generation) = bumpGeneration(server)
        val controller = HostController(
            registry = InMemoryWorldRegistry(SystemClock),
            lanOpener = MinecraftLanOpener(server),
            endpointResolver = LocalEndpointResolver(),
            nodeId = NodeId.random(), // TODO(persist): stable per-install identity once the DHT is live
            clock = SystemClock,
        )
        val result = runBlocking { controller.host(worldId, generation) }
        if (result is HostResult.Hosting) {
            HostSession.install(controller)
            JukzMod.logger.info("jukz: hosting {} on port {}", result.shortCode, result.port)
        } else {
            controller.close()
        }
        return result
    }

    /** Bump and persist the fencing generation on the server thread; return the world id + new gen. */
    private fun bumpGeneration(server: IntegratedServer): Pair<WorldId, Long> {
        val future = CompletableFuture<Pair<WorldId, Long>>()
        server.execute {
            val state = WorldIdState.get(server.overworld)
            val generation = state.incrementGeneration()
            future.complete(WorldId.of(state.worldId) to generation)
        }
        return future.get()
    }

    private fun applyResult(client: MinecraftClient, result: HostResult, parent: Screen?) {
        when (result) {
            is HostResult.Hosting ->
                client.setScreen(HostInfoScreen(parent))
            is HostResult.Superseded ->
                client.setScreen(
                    HostErrorScreen(
                        "This world is already being hosted somewhere else.",
                        onRetry = { share(parent) },
                        onBack = { client.setScreen(parent) },
                    ),
                )
            is HostResult.Failed ->
                client.setScreen(
                    HostErrorScreen(
                        result.reason,
                        onRetry = { share(parent) },
                        onBack = { client.setScreen(parent) },
                    ),
                )
        }
    }
}
