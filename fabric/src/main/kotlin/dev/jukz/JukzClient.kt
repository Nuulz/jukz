package dev.jukz

import dev.jukz.client.GuestSession
import dev.jukz.client.HostCoordinator
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.client.gui.JoinPromptScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Client entrypoint. No mixins here (both UI hooks ride `ScreenEvents.AFTER_INIT`); the auto-join
 * mixin lives separately.
 *  - Multiplayer screen: inject a "Play together" button (join-by-code flow).
 *  - Every integrated world auto-hosts once the local player has joined (so others can join), via
 *    `ClientPlayConnectionEvents.JOIN`.
 *  - Pause menu (singleplayer host): replace vanilla "Open to LAN" with "World info (jukz)", which
 *    opens the host status panel — the world is already shared, so this is informational.
 */
object JukzClient : ClientModInitializer {
    override fun onInitializeClient() {
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            when (screen) {
                is MultiplayerScreen -> {
                    val button = ButtonWidget.builder(Text.literal("Play together")) {
                        client.setScreen(JoinPromptScreen(screen))
                    }.dimensions(scaledWidth - 160, 6, 150, 20).build()
                    Screens.getButtons(screen).add(button)
                }

                is GameMenuScreen ->
                    if (client.isIntegratedServerRunning) replaceOpenToLanButton(screen)
            }
        }

        // Every jukz world is permanently shareable: opening it (when nobody else hosts it) puts it
        // online automatically so others can join. This MUST fire on the *client* JOIN event, not
        // SERVER_STARTED: opening to LAN reads client.player internally, which only exists once the
        // local player has actually spawned (SERVER_STARTED is ~1s too early and NPEs). A guest join
        // to a remote host has no integrated server (client.server == null), so this correctly fires
        // only for locally-opened worlds.
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.server?.let { HostCoordinator.autoHost(it) }
        }

        // Leaving a host's world tears down the guest join session, so its handoff watcher does not
        // outlive the visit — otherwise a later host-leave pops a stale "Host now" at someone who is
        // back at the menu. A disconnect that is part of an in-progress handoff is left alone (that
        // flow leaves the world on purpose and owns its own teardown).
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            if (GuestSession.isActive && !GuestSession.handoffOffered) GuestSession.leave()
        }

        JukzMod.logger.info("jukz initialized")
    }

    /**
     * Add a "World info (jukz)" button to the pause menu (share code, identity, endpoint, live
     * status). When the vanilla "Open to LAN" button is present we take its slot and hide it (the
     * world is already auto-shared, so it would be redundant); once jukz has opened the world to LAN
     * that vanilla button disappears, so we then pin ours top-left instead. Either way the button is
     * always available while hosting. Matching is by the resolved label of `menu.shareToLan`, which
     * both sides resolve through the same Language, so it is locale-independent.
     */
    private fun replaceOpenToLanButton(screen: GameMenuScreen) {
        val buttons = Screens.getButtons(screen)
        val lanLabel = Text.translatable("menu.shareToLan").string
        val lan = buttons.firstOrNull { it.message.string == lanLabel }

        val info = ButtonWidget.builder(Text.literal("World info (jukz)")) {
            MinecraftClient.getInstance().setScreen(HostInfoScreen(screen))
        }
        if (lan != null) {
            lan.visible = false
            lan.active = false
            info.dimensions(lan.x, lan.y, lan.width, lan.height)
        } else {
            info.dimensions(8, 8, 150, 20)
        }
        buttons.add(info.build())
    }
}
