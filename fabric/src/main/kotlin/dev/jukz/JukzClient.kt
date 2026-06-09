package dev.jukz

import dev.jukz.client.gui.JoinPromptScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Client entrypoint. Injects a "Join via jukz" button into the title screen (no mixin needed,
 * via ScreenEvents.AFTER_INIT) which opens the join-by-code flow.
 */
object JukzClient : ClientModInitializer {
    override fun onInitializeClient() {
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            if (screen is TitleScreen) {
                val button = ButtonWidget.builder(Text.literal("Join via jukz")) {
                    client.setScreen(JoinPromptScreen(screen))
                }.dimensions(scaledWidth / 2 - 100, scaledHeight / 4 + 96, 200, 20).build()
                Screens.getButtons(screen).add(button)
            }
        }
        JukzMod.logger.info("jukz client initialized")
    }
}
