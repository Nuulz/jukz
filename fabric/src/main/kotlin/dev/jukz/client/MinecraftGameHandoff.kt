package dev.jukz.client

import dev.jukz.core.join.GameHandoff
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.multiplayer.ServerData

/**
 * Real [GameHandoff]: points the vanilla multiplayer connect flow at the loopback relay. Minecraft's
 * own protocol + encryption then ride transparently over the jukz transport. Runs on the client
 * thread via [Minecraft.execute] since it touches screen state.
 */
class MinecraftGameHandoff(private val parent: () -> Screen?) : GameHandoff {
    override fun connect(host: String, port: Int) {
        val client = Minecraft.getInstance()
        client.execute {
            val info = ServerData("jukz host", "$host:$port", ServerData.Type.OTHER)
            val screen = parent() ?: TitleScreen()
            ConnectScreen.startConnecting(screen, client, ServerAddress.parseString("$host:$port"), info, false, null)
        }
    }
}
