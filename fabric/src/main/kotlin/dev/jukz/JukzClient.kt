package dev.jukz

import dev.jukz.client.ShareCoordinator
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.client.gui.JoinPromptScreen
import dev.jukz.runtime.HostSession
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Client entrypoint. No mixins: both UI hooks ride `ScreenEvents.AFTER_INIT`.
 *  - Title screen: inject a "Join via jukz" button (join-by-code flow).
 *  - Pause menu (singleplayer host): replace vanilla "Open to LAN" with "Play together", which opens
 *    the world to jukz (reachable beyond the LAN, not just the local network) and shows the code.
 */
object JukzClient : ClientModInitializer {
    override fun onInitializeClient() {
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            when (screen) {
                is TitleScreen -> {
                    val button = ButtonWidget.builder(Text.literal("Join via jukz")) {
                        client.setScreen(JoinPromptScreen(screen))
                    }.dimensions(scaledWidth / 2 - 100, scaledHeight / 4 + 96, 200, 20).build()
                    Screens.getButtons(screen).add(button)
                }

                is GameMenuScreen ->
                    if (client.isIntegratedServerRunning) replaceOpenToLanButton(screen)
            }
        }
        JukzMod.logger.info("jukz client initialized")
    }

    /**
     * Swap the vanilla "Open to LAN" button in the same slot. Before sharing it reads "Play together"
     * and opens the world to jukz; once hosting it becomes "Hosting · world info" and opens the host
     * status panel. The vanilla button is hidden and disabled (rather than removed) so it can never
     * capture a click. Matching is by the resolved label of `menu.shareToLan`, which both sides
     * resolve through the same Language, so it is locale-independent.
     */
    private fun replaceOpenToLanButton(screen: GameMenuScreen) {
        val buttons = Screens.getButtons(screen)
        val lanLabel = Text.translatable("menu.shareToLan").string
        val lan = buttons.firstOrNull { it.message.string == lanLabel } ?: return

        lan.visible = false
        lan.active = false

        val builder = if (HostSession.isHosting) {
            ButtonWidget.builder(Text.literal("Hosting · world info")) {
                MinecraftClient.getInstance().setScreen(HostInfoScreen(screen))
            }
        } else {
            ButtonWidget.builder(Text.literal("Play together")) {
                ShareCoordinator.share(screen)
            }
        }
        buttons.add(builder.dimensions(lan.x, lan.y, lan.width, lan.height).build())
    }
}
