package dev.jukz.client

import dev.jukz.core.host.LanOpener
import net.minecraft.client.MinecraftClient
import net.minecraft.server.integrated.IntegratedServer
import net.minecraft.world.GameMode
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture

/**
 * Real [LanOpener]: opens the integrated world to the network via the vanilla
 * `IntegratedServer.openToLan` (signature verified for 1.21.1). The call is marshalled to the client
 * render thread — vanilla's own "Open to LAN" screen invokes it from there — even though the jukz
 * host flow runs on a background thread. A free port is grabbed with a throwaway [ServerSocket] (the
 * same trick `NetworkUtils.findLocalPort` uses) and handed to `openToLan`.
 *
 * If the world is already published (a second "Play together" click in the same session), the
 * existing LAN port is returned instead of re-binding. The shared game mode follows the host's
 * current one; cheats stay off by default.
 */
class MinecraftLanOpener(
    private val server: IntegratedServer,
    private val allowCheats: Boolean = false,
) : LanOpener {

    override fun open(): Int? {
        val client = MinecraftClient.getInstance()
        if (client.isOnThread) return openOnRenderThread(client)
        val future = CompletableFuture<Int?>()
        client.execute { future.complete(openOnRenderThread(client)) }
        return future.get()
    }

    private fun openOnRenderThread(client: MinecraftClient): Int? {
        if (server.isRemote) return server.serverPort // already shared this session
        // openToLan() dereferences client.player internally; bail out (rather than NPE) if the local
        // player hasn't spawned yet. The caller triggers this from ClientPlayConnectionEvents.JOIN, so
        // in practice the player is always present — this is belt-and-suspenders against re-ordering.
        if (client.player == null) return null
        val gameMode = client.interactionManager?.currentGameMode ?: GameMode.SURVIVAL
        val port = ServerSocket(0).use { it.localPort }
        if (!server.openToLan(gameMode, allowCheats, port)) return null
        // jukz authorizes guests via the world code, not Mojang. Online-mode auth only gets in the
        // way: a guest reaching the integrated server through the jukz relay would be kicked during
        // login for an "invalid session" (offline/dev accounts have none). Drop it so login is direct.
        server.isOnlineMode = false
        return port
    }
}
