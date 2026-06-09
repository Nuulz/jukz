package dev.jukz.client

import dev.jukz.core.join.GameHandoff
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo

/**
 * Real [GameHandoff]: points the vanilla multiplayer connect flow at the loopback relay. Minecraft's
 * own protocol + encryption then ride transparently over the jukz transport. Runs on the client
 * thread via [MinecraftClient.execute] since it touches screen state.
 */
class MinecraftGameHandoff(private val parent: () -> Screen?) : GameHandoff {
    override fun connect(host: String, port: Int) {
        val client = MinecraftClient.getInstance()
        client.execute {
            val info = ServerInfo("jukz host", "$host:$port", ServerInfo.ServerType.OTHER)
            val screen = parent() ?: TitleScreen()
            ConnectScreen.connect(screen, client, ServerAddress.parse("$host:$port"), info, false, null)
        }
    }
}
