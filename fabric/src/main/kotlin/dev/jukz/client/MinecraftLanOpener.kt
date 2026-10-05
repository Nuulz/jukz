package dev.jukz.client

import dev.jukz.JukzMod
import dev.jukz.config.JukzConfig
import dev.jukz.core.host.LanOpener
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.server.IntegratedServer
import net.minecraft.world.level.GameType
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture

/**
 * Real [LanOpener]: opens the integrated world to the network via the vanilla
 * `IntegratedServer.publishServer` (signature verified for 1.21.1). The call is marshalled to the client
 * render thread — vanilla's own "Open to LAN" screen invokes it from there — even though the jukz
 * host flow runs on a background thread. A free port is grabbed with a throwaway [ServerSocket] (the
 * same trick `NetworkUtils.findLocalPort` uses) and handed to `publishServer`.
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
        val client = Minecraft.getInstance()
        if (client.isSameThread) return openOnRenderThread(client)
        val future = CompletableFuture<Int?>()
        client.execute { future.complete(openOnRenderThread(client)) }
        return future.get()
    }

    private fun openOnRenderThread(client: Minecraft): Int? {
        if (server.isPublished) return server.port // already shared this session
        // publishServer() dereferences client.player internally; bail out (rather than NPE) if the local
        // player hasn't spawned yet. The caller triggers this from ClientPlayConnectionEvents.JOIN, so
        // in practice the player is always present — this is belt-and-suspenders against re-ordering.
        if (client.player == null) return null
        val gameMode = client.gameMode?.playerMode ?: GameType.SURVIVAL
        val port = ServerSocket(0).use { it.localPort }
        if (!server.publishServer(gameMode, allowCheats, port)) return null
        // Guests are verified by Mojang like on any server (the jukz relay only moves bytes), unless the
        // host has no real account (dev runs, offline launchers) or opted into offline guests. See
        // GuestAdmission for why offline mode used to be a hole.
        val premium = client.user.type.let {
            it == User.Type.MSA || it == User.Type.MOJANG
        }
        server.setUsesAuthentication(GuestAdmission.onlineMode(premium, JukzConfig.offlineGuests))
        if (!server.usesAuthentication()) {
            JukzMod.logger.info("jukz: guests join in offline mode (host account: {}, offline-guests: {})", client.user.type, JukzConfig.offlineGuests)
        }
        return port
    }
}
