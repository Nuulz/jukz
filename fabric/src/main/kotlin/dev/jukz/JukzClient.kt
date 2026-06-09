package dev.jukz

import dev.jukz.client.HostCoordinator
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.client.gui.JoinPromptScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.server.integrated.IntegratedServer
import net.minecraft.text.Text

/**
 * Client entrypoint. No mixins here (both UI hooks ride `ScreenEvents.AFTER_INIT`); the auto-join
 * mixin lives separately.
 *  - Title screen: inject a "Join via jukz" button (join-by-code flow).
 *  - Every integrated world auto-hosts on start (so others can join), via `SERVER_STARTED`.
 *  - Pause menu (singleplayer host): replace vanilla "Open to LAN" with "World info (jukz)", which
 *    opens the host status panel — the world is already shared, so this is informational.
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

        // Every jukz world is permanently shareable: opening it (when nobody else hosts it) puts it
        // online automatically so others can join. The world-open interceptor handles the other case
        // (a live host elsewhere -> join as guest, so no integrated server starts and this won't fire).
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (server is IntegratedServer) HostCoordinator.autoHost(server)
        }

        JukzMod.logger.info("jukz client initialized")
    }

    /**
     * Swap the vanilla "Open to LAN" button for "World info (jukz)" in the same slot. The world is
     * already auto-shared, so the button is informational: it opens [HostInfoScreen] (share code,
     * identity, endpoint, live status). The vanilla button is hidden and disabled (rather than
     * removed) so it can never capture a click. Matching is by the resolved label of `menu.shareToLan`,
     * which both sides resolve through the same Language, so it is locale-independent.
     */
    private fun replaceOpenToLanButton(screen: GameMenuScreen) {
        val buttons = Screens.getButtons(screen)
        val lanLabel = Text.translatable("menu.shareToLan").string
        val lan = buttons.firstOrNull { it.message.string == lanLabel } ?: return

        lan.visible = false
        lan.active = false
        buttons.add(
            ButtonWidget.builder(Text.literal("World info (jukz)")) {
                MinecraftClient.getInstance().setScreen(HostInfoScreen(screen))
            }.dimensions(lan.x, lan.y, lan.width, lan.height).build(),
        )
    }
}
