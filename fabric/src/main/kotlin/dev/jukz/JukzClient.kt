package dev.jukz

import dev.jukz.client.GuestSession
import dev.jukz.client.HostCoordinator
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.client.gui.HostLeavingScreen
import dev.jukz.client.gui.JoinPromptScreen
import dev.jukz.client.gui.WorldListLiveBadge
import dev.jukz.core.model.WorldId
import dev.jukz.world.WorldIdSidecar
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.DisconnectedScreen
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.screen.world.SelectWorldScreen
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

                is SelectWorldScreen -> addCopyCodeButton(screen, scaledHeight)
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

        // When a guest's game connection drops, just timestamp it — do NOT close the controller, or its
        // control channel would close too and the host (which counts live guests) would skip the
        // handoff. A *server-side* drop (the host left) shows the vanilla "Connection lost"
        // DisconnectedScreen; swap it for a jukz wait so the takeover prompt can take over. A voluntary
        // leave goes to the title screen (not a DisconnectedScreen), so it is left untouched.
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            if (GuestSession.isActive) {
                GuestSession.markDisconnected()
                client.execute {
                    if (GuestSession.recentlyEngaged() && client.currentScreen is DisconnectedScreen) {
                        client.setScreen(HostLeavingScreen())
                    }
                }
            }
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

    /**
     * Add a "Copy jukz code" button to the world-select screen. It copies the last-clicked jukz world's
     * share code to the clipboard so the player can share it without opening the world. Bottom-left, in
     * the side margin clear of the vanilla button block.
     */
    private fun addCopyCodeButton(screen: SelectWorldScreen, scaledHeight: Int) {
        val button = ButtonWidget.builder(Text.literal("Copy jukz code")) { btn ->
            val level = WorldListLiveBadge.selectedLevelName()
            val code = level?.let { jukzCodeFor(it) }
            btn.message = when {
                code != null -> {
                    MinecraftClient.getInstance().keyboard.clipboard = code
                    Text.literal("Code copied!")
                }
                level != null -> Text.literal("Not a jukz world")
                else -> Text.literal("Click a world first")
            }
        }.dimensions(4, scaledHeight - 24, 110, 20).build()
        Screens.getButtons(screen).add(button)
    }

    /** The jukz share code for a save folder, or null if it is not a jukz world. */
    private fun jukzCodeFor(levelName: String): String? = runCatching {
        val saveRoot = MinecraftClient.getInstance().levelStorage.savesDirectory.resolve(levelName)
        WorldIdSidecar.read(saveRoot)?.let { WorldId.of(it.worldId).shortCode() }
    }.getOrNull()
}
