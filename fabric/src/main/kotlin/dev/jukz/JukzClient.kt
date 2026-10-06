package dev.jukz

import dev.jukz.compat.hasOverlay
import dev.jukz.compat.currentScreen
import dev.jukz.compat.openScreen
import dev.jukz.compat.windowHandle
import dev.jukz.client.GuestSession
import dev.jukz.client.JoinCoordinator
import dev.jukz.client.HostCoordinator
import dev.jukz.client.CloudWorlds
import dev.jukz.client.gui.AccountScreen
import dev.jukz.client.gui.CosmeticsScreen
import dev.jukz.client.gui.IconButton
import dev.jukz.client.gui.SupportScreen
import dev.jukz.client.gui.UpdateScreen
import dev.jukz.client.gui.UiIcons
import dev.jukz.config.JukzState
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.TitleScreen
import dev.jukz.client.gui.HostInfoScreen
import dev.jukz.cosmetics.Cosmetics
import dev.jukz.client.gui.LimitedScreen
import dev.jukz.compat.toast
import dev.jukz.config.JukzConfig
import dev.jukz.discovery.DeviceIdentity
import dev.jukz.cosmetics.CosmeticsFeatureRenderer
//? if >=26.2 {
/*import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback
//?}
//? if >=1.21.11 {
/*import dev.jukz.cosmetics.GuiModelRenderer
//? if >=26.2 {
/*import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry
//?}
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
*///?} else {
import net.minecraft.client.renderer.entity.player.PlayerRenderer
//?}
import dev.jukz.client.gui.HostLeavingScreen
import dev.jukz.client.gui.UiHotReload
import dev.jukz.net.WorldAccessPayload
import dev.jukz.net.LoadoutPayload
import dev.jukz.net.SkinPayload
import dev.jukz.skins.LocalSkins
import dev.jukz.world.WorldKeyStore
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import dev.jukz.client.gui.JoinPromptScreen
import dev.jukz.client.gui.UploadingWorldScreen
import dev.jukz.client.gui.WorldListLiveBadge
import dev.jukz.core.model.WorldId
import dev.jukz.runtime.GhostUpload
import dev.jukz.world.WorldIdSidecar
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import dev.jukz.compat.screenWidgets
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWWindowCloseCallback
import org.lwjgl.glfw.GLFWWindowCloseCallbackI

/**
 * Client entrypoint. No mixins here (both UI hooks ride `ScreenEvents.AFTER_INIT`); the auto-join
 * mixin lives separately.
 *  - Multiplayer screen: inject a "Play together" button (join-by-code flow) and "Cosmetics" (badges).
 *  - Every integrated world auto-hosts once the local player has joined (so others can join), via
 *    `ClientPlayConnectionEvents.JOIN`.
 *  - Pause menu (singleplayer host): replace vanilla "Open to LAN" with "World info (jukz)", which
 *    opens the host status panel — the world is already shared, so this is informational.
 */
object JukzClient : ClientModInitializer {
    override fun onInitializeClient() {
        UiHotReload.install() // dev runs only: owo-ui models are read live from src/

        // Anti-abuse: a premium session raises the limits; a refusal while opening a world is a toast.
        DeviceIdentity.sessionToken = { Cosmetics.sessionToken() }
        DeviceIdentity.onLimited = { secs ->
            val client = Minecraft.getInstance()
            client.execute {
                client.toast(
                    Component.literal("World not shared online"),
                    Component.literal("Too many worlds opened. It goes online in ${LimitedScreen.waitText(secs)}."),
                )
            }
        }

        // 3D cosmetics (hats, face and back pieces) on every player model.
        //? if >=26.2 {
        /*LivingEntityRenderLayerRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
        *///?} else {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
        //?}
            //? if >=1.21.11 {
            /*@Suppress("UNCHECKED_CAST")
            if (renderer is AvatarRenderer<*>) helper.register(CosmeticsFeatureRenderer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
            *///?} else {
            if (renderer is PlayerRenderer) helper.register(CosmeticsFeatureRenderer(renderer))
            //?}
        }
        //? if >=1.21.11 {
        /*// The player preview's 3D picture (see GuiModelRenderer).
        //? if >=26.2 {
        /*PictureInPictureRendererRegistry.register { GuiModelRenderer() }
        *///?} else {
        SpecialGuiElementRegistry.register { ctx -> GuiModelRenderer(ctx.vertexConsumers()) }
        //?}
        *///?}

        // The host lets us in: keep the world key (to revive it from the cloud later) and the handoff gate.
        ClientPlayNetworking.registerGlobalReceiver(WorldAccessPayload.ID) { payload, _ ->
            WorldKeyStore.rememberFromHost(WorldId.of(payload.worldId), payload.key)
            // Entered through Minecraft's own LAN list instead of jukz: watch the host now, so the
            // world can still be handed to us. (install() keeps the gate, so set it after.)
            JoinCoordinator.attachIfMissing(WorldId.of(payload.worldId))
            GuestSession.onWorldAccess(payload.gate)
        }

        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            when (screen) {
                is JoinMultiplayerScreen -> {
                    val button = Button.builder(Component.literal("Play together")) {
                        client.openScreen(JoinPromptScreen(screen))
                    }.bounds(scaledWidth - 160, 6, 150, 20).build()
                    screenWidgets(screen).add(button)
                    val cosmetics = Button.builder(Component.literal("Cosmetics")) {
                        client.openScreen(CosmeticsScreen(screen))
                    }.bounds(10, 6, 90, 20).build()
                    screenWidgets(screen).add(cosmetics)
                }

                is PauseScreen -> {
                    if (client.hasSingleplayerServer()) replaceOpenToLanButton(screen)
                    // Change cosmetics without leaving the world (host or guest): bottom-left corner, clear
                    // of World info (top-left) and of toasts (top-right).
                    screenWidgets(screen).add(IconButton(8, scaledHeight - IconButton.SIZE - 8, UiIcons::jukz, Component.literal("jukz cosmetics")) {
                        client.openScreen(CosmeticsScreen(screen))
                    })
                }

                is SelectWorldScreen -> {
                    addCopyCodeButton(screen, scaledWidth, scaledHeight)
                    // Your account and your worlds on other PCs: top-right, clear of the search box.
                    screenWidgets(screen).add(Button.builder(Component.literal("My account")) {
                        client.openScreen(AccountScreen(screen))
                    }.bounds(scaledWidth - 84, 4, 80, 20).build())
                }

                is TitleScreen -> addTitleButtons(screen)
            }
        }

        // Once per version, on the title screen after the loading overlay: a fresh install gets the welcome
        // (with Ko-fi), an update gets what's new.
        var versionChecked = false
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            val title = client.currentScreen as? TitleScreen ?: return@EndTick
            if (versionChecked || client.hasOverlay) return@EndTick
            versionChecked = true
            Cosmetics.ensureSignedIn() // on the menu already: your cloud worlds and cosmetics are ready sooner
            JukzConfig.rendezvousUrl?.let { DeviceIdentity.ensureRegisteredAsync(it) } // anti-abuse: once per install
            if (!JukzState.versionChanged()) return@EndTick
            client.openScreen(if (JukzState.firstRun()) SupportScreen(title) else UpdateScreen(title, JukzState.lastVersion()))
        })

        // Worlds from your other PCs (premium accounts): brought over once you're signed in, while you're in
        // the menus — never in the middle of a world.
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (client.level == null && client.currentScreen is TitleScreen) CloudWorlds.bringNewOnce()
        })

        // Every jukz world is permanently shareable: opening it (when nobody else hosts it) puts it
        // online automatically so others can join. This MUST fire on the *client* JOIN event, not
        // SERVER_STARTED: opening to LAN reads client.player internally, which only exists once the
        // local player has actually spawned (SERVER_STARTED is ~1s too early and NPEs). A guest join
        // to a remote host has no integrated server (client.singleplayerServer == null), so this correctly fires
        // only for locally-opened worlds.
        // Skins chosen in the hub: ours (saved on this PC) and friends' (over the game connection).
        LocalSkins.init()
        ClientPlayNetworking.registerGlobalReceiver(SkinPayload.ID) { payload, _ -> LocalSkins.receive(payload) }
        ClientPlayNetworking.registerGlobalReceiver(LoadoutPayload.ID) { payload, _ -> Cosmetics.receive(payload) }
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { LocalSkins.tick(); Cosmetics.tick() })

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            LocalSkins.shareSoon()
            Cosmetics.shareSoon()
            client.singleplayerServer?.let { HostCoordinator.autoHost(it) }
            Cosmetics.ensureSignedIn() // registers us, so others see our tab-list badge
        }

        // When a guest's game connection drops, just timestamp it — do NOT close the controller, or its
        // control channel would close too and the host (which counts live guests) would skip the
        // handoff. A *server-side* drop (the host left) shows the vanilla "Connection lost"
        // DisconnectedScreen; swap it for a jukz wait so the takeover prompt can take over. A voluntary
        // leave goes to the title screen (not a DisconnectedScreen), so it is left untouched.
        //
        // MinecraftDisconnectMixin already performs this swap at the source (so "Connection lost"
        // never flashes); this reactive handler is a fallback for the case where that injection does not
        // apply (require = 0). It is a no-op when the mixin already replaced the screen.
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            LocalSkins.forgetFriends()
            Cosmetics.forgetFriends()
            if (GuestSession.isActive) {
                GuestSession.markDisconnected()
                client.execute {
                    if (GuestSession.recentlyEngaged() && client.currentScreen is DisconnectedScreen) {
                        client.openScreen(HostLeavingScreen())
                    }
                }
            }
        }

        // Surface the ghost-upload screen once a guest-less close has armed it. It cannot be shown from
        // inside Minecraft.disconnect(): disconnect() consumes its screen early (via reset) and
        // PauseScreen sets a fresh TitleScreen *after* disconnect() returns, overriding anything the
        // disconnect path installed — and the upload is only armed during the SERVER_STOPPING that the
        // same disconnect() drives. So we react here, after the world is gone and the pack is ready
        // (pending != null). This tick runs after that TitleScreen, so the screen sticks; the upload
        // screen clears GhostUpload when it finishes, so this fires exactly once per guest-less close.
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (client.level == null &&
                GhostUpload.pending() != null &&
                client.currentScreen !is UploadingWorldScreen
            ) {
                client.openScreen(UploadingWorldScreen())
            }
        })

        // Veto the window X while a ghost upload is in progress, so an accidental close doesn't
        // abandon the backup. Registered on the first tick (the window + Minecraft's own close
        // callback exist by then); we chain to Minecraft's callback for every non-upload close so
        // normal quitting still works. An OS force-kill remains uncatchable, by design.
        val installed = java.util.concurrent.atomic.AtomicBoolean(false)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            if (!installed.compareAndSet(false, true)) return@EndTick
            val handle = client.windowHandle
            var previous: GLFWWindowCloseCallback? = null
            previous = GLFW.glfwSetWindowCloseCallback(handle, GLFWWindowCloseCallbackI { window ->
                val screen = client.currentScreen
                if (screen is UploadingWorldScreen && screen.isUploading()) {
                    GLFW.glfwSetWindowShouldClose(window, false) // veto
                    JukzMod.logger.info("jukz: window close vetoed — world still uploading")
                } else {
                    previous?.invoke(window) // hand off to Minecraft's own close handling
                }
            })
        })

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
    private fun replaceOpenToLanButton(screen: PauseScreen) {
        val buttons = screenWidgets(screen)
        //? if >=26.2 {
        /*// 26.x: "Multiplayer options" (shown as Open to LAN), which could switch the sharing jukz relies on off.
        val lanLabel = Component.translatable("menu.multiplayerOptions.button").string
        *///?} else {
        val lanLabel = Component.translatable("menu.shareToLan").string
        //?}
        val lan = buttons.firstOrNull { it.message.string == lanLabel }

        val info = Button.builder(Component.literal("World info (jukz)")) {
            Minecraft.getInstance().openScreen(HostInfoScreen(screen))
        }
        if (lan != null) {
            lan.visible = false
            lan.active = false
            info.bounds(lan.x, lan.y, lan.width, lan.height)
        } else {
            info.bounds(8, 8, 150, 20)
        }
        buttons.add(info.build())
    }

    /**
     * Add a "Copy jukz code" button to the world-select screen. It copies the last-clicked jukz world's
     * share code to the clipboard so the player can share it without opening the world. Bottom-left, in
     * the side margin beside the vanilla button block — or top-left when that margin is too narrow (a
     * small window / large GUI scale), where it would otherwise cover the vanilla "Edit" button.
     */
    private fun addCopyCodeButton(screen: SelectWorldScreen, scaledWidth: Int, scaledHeight: Int) {
        val button = Button.builder(Component.literal("Copy jukz code")) { btn ->
            val level = WorldListLiveBadge.selectedLevelName()
            val code = level?.let { jukzCodeFor(it) }
            btn.message = when {
                code != null -> {
                    Minecraft.getInstance().keyboardHandler.clipboard = code
                    Component.literal("Code copied!")
                }
                level != null -> Component.literal("Not a jukz world")
                else -> Component.literal("Click a world first")
            }
        }.bounds(4, copyButtonY(scaledWidth, scaledHeight), COPY_BUTTON_WIDTH, 20).build()
        screenWidgets(screen).add(button)
    }

    private const val COPY_BUTTON_WIDTH = 110

    /**
     * Two icon buttons on the title screen, flanking the Options/Quit row like vanilla's language and
     * accessibility buttons: jukz cosmetics on the left, Ko-fi on the right. Placed relative to the
     * Options button, found by its label.
     */
    private fun addTitleButtons(screen: TitleScreen) {
        val buttons = screenWidgets(screen)
        val optionsLabel = Component.translatable("menu.options").string
        val options = buttons.firstOrNull { it.message.string == optionsLabel } ?: return
        val y = options.y
        val left = options.x - 24 - 24 // past vanilla's language button
        val right = options.x + 200 + 4 + 24 // past vanilla's accessibility button
        buttons.add(IconButton(left - 24, y, { UiIcons.ACCOUNT }, Component.literal("Your jukz account")) {
            Minecraft.getInstance().openScreen(AccountScreen(screen))
        })
        buttons.add(IconButton(left, y, UiIcons::jukz, Component.literal("jukz cosmetics")) {
            Minecraft.getInstance().openScreen(CosmeticsScreen(screen))
        })
        buttons.add(IconButton(right, y, { UiIcons.KOFI }, Component.literal("Support jukz on Ko-fi")) {
            ConfirmLinkScreen.confirmLinkNow(screen, CosmeticsScreen.KOFI_URL)
        })
    }

    /** Vanilla's world-select bottom buttons span 308 px centred; keep 4 px clear of them. */
    internal fun copyButtonY(scaledWidth: Int, scaledHeight: Int): Int {
        val vanillaLeft = scaledWidth / 2 - 154
        return if (4 + COPY_BUTTON_WIDTH + 4 <= vanillaLeft) scaledHeight - 24 else 4
    }

    /** The jukz share code for a save folder, or null if it is not a jukz world. */
    private fun jukzCodeFor(levelName: String): String? = runCatching {
        val saveRoot = Minecraft.getInstance().levelSource.baseDir.resolve(levelName)
        WorldIdSidecar.read(saveRoot)?.let { WorldId.of(it.worldId).shortCode() }
    }.getOrNull()
}
