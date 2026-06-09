package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.client.gui.ConnectingScreen
import dev.jukz.client.gui.NatErrorScreen
import dev.jukz.client.gui.SearchingHostScreen
import dev.jukz.client.gui.ShouldHostScreen
import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.join.GameHandoff
import dev.jukz.core.join.JoinController
import dev.jukz.core.join.JoinResult
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.DirectTcpTransport
import dev.jukz.core.transport.Transport
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the guest join flow on the client: shows the searching screen, runs [JoinController] off
 * the render thread, and maps each [JoinResult] back to a screen on the client thread. Every path
 * is wrapped so a failure surfaces as a readable screen, never a crash.
 *
 * Discovery and transport are injected; the defaults are the loopback-safe fakes. The real DHT /
 * NAT adapters drop in here behind the same interfaces without touching this class. With the
 * in-memory registry there is no live host to find, so a code lookup ends cleanly on the
 * "nobody is hosting" screen rather than connecting — that is expected until the DHT is wired.
 */
object JoinCoordinator {

    fun start(
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
        registry: WorldRegistry = InMemoryWorldRegistry(SystemClock),
        transport: Transport = DirectTcpTransport(),
    ) {
        val client = MinecraftClient.getInstance()
        val handoff: GameHandoff = MinecraftGameHandoff { parent }
        val controller = JoinController(registry, transport, handoff, SystemClock)
        val cancelled = AtomicBoolean(false)

        val onCancel = {
            cancelled.set(true)
            controller.close()
            client.execute { client.setScreen(parent) }
        }

        client.setScreen(SearchingHostScreen(shortCode, onCancel))

        Thread {
            val result = try {
                runBlocking { controller.join(worldId) }
            } catch (e: Throwable) {
                JoinResult.Failed(e.message ?: e.toString())
            }
            if (cancelled.get()) return@Thread
            client.execute { applyResult(client, result, worldId, shortCode, parent) }
        }.apply {
            isDaemon = true
            name = "jukz-join"
        }.start()
    }

    private fun applyResult(
        client: MinecraftClient,
        result: JoinResult,
        worldId: WorldId,
        shortCode: String,
        parent: Screen?,
    ) {
        when (result) {
            is JoinResult.Connected -> {
                // The hand-off already opened the connect screen; nothing more to do.
                JukzMod.logger.info("jukz: joined host at {}:{}", result.host, result.port)
            }
            JoinResult.HostUnavailable ->
                client.setScreen(
                    ShouldHostScreen(
                        "No live host was found for $shortCode.",
                        onBack = { client.setScreen(parent) },
                    ),
                )
            is JoinResult.ShouldHost ->
                client.setScreen(
                    ShouldHostScreen(
                        "The announced host stopped responding.",
                        onBack = { client.setScreen(parent) },
                    ),
                )
            is JoinResult.Failed ->
                client.setScreen(
                    NatErrorScreen(
                        result.reason,
                        onRetry = { start(worldId, shortCode, parent) },
                        onHostLocally = { client.setScreen(parent) },
                    ),
                )
        }
    }
}
