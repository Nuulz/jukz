// Client and server calls whose shape changed between the Minecraft versions jukz builds for.
package dev.jukz.compat

import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftSessionService
import dev.jukz.compat.GuiGraphics
import dev.jukz.core.model.GameVersion
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import net.minecraft.client.gui.screens.Screen
import net.minecraft.server.MinecraftServer
//? if >=1.21.11 {
/*import net.minecraft.server.players.NameAndId
*///?} else {
import net.minecraft.client.User
//?}

/** A short system toast (top right) with [title] and [body]. */
fun Minecraft.toast(title: Component, body: Component) =
    //? if >=26.2 {
    /*SystemToast.addOrUpdate(gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, body)
    *///?} else if >=1.21.11 {
    /*SystemToast.addOrUpdate(toastManager, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, body)
    *///?} else {
    SystemToast.addOrUpdate(toasts, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, body)
    //?}

/** Mojang's session service (joinServer / hasJoined). */
//? if >=1.21.11 {
/*val Minecraft.jukzSessionService: MinecraftSessionService get() = services().sessionService()
*///?} else {
val Minecraft.jukzSessionService: MinecraftSessionService get() = minecraftSessionService
//?}

/** The GLFW window handle. */
//? if >=1.21.11 {
/*val Minecraft.windowHandle: Long get() = window.handle()
*///?} else {
val Minecraft.windowHandle: Long get() = window.window
//?}

/**
 * Whether the game is signed in with a real (Microsoft) account, which is what lets guests be verified
 * by Mojang. Newer versions dropped the account type; a Microsoft session's access token is a JWT.
 */
//? if >=1.21.11 {
/*val Minecraft.hasRealAccount: Boolean get() = !isOfflineDeveloperMode && user.accessToken.count { it == '.' } >= 2
*///?} else {
val Minecraft.hasRealAccount: Boolean get() = user.type.let { it == User.Type.MSA || it == User.Type.MOJANG }
//?}

/** A short name for the account type, for logs. */
//? if >=1.21.11 {
/*val Minecraft.accountKind: String get() = if (hasRealAccount) "msa" else "offline"
*///?} else {
val Minecraft.accountKind: String get() = user.type.toString()
//?}

/** Is [profile] the player who opened this integrated server? */
//? if >=1.21.11 {
/*fun MinecraftServer.isOwner(profile: GameProfile): Boolean = isSingleplayerOwner(NameAndId(profile))
*///?} else {
fun MinecraftServer.isOwner(profile: GameProfile): Boolean = isSingleplayerOwner(profile)
//?}

/** Leave the world (saving it when it is ours) and show [next]. */
//? if >=1.21.11 {
/*fun Minecraft.leaveWorld(next: Screen) = disconnect(next, false)
*///?} else {
fun Minecraft.leaveWorld(next: Screen) = disconnect(next)
//?}

/** Tell the client level it is being left (before [leaveWorld], as the vanilla quit button does). */
//? if >=1.21.11 {
/*fun Minecraft.closeLevel() = level?.disconnect(Component.translatable("menu.savingLevel"))
*///?} else {
fun Minecraft.closeLevel() = level?.disconnect()
//?}

/** The Minecraft version this game runs: its name and the data version it writes into saves. */
//? if >=1.21.11 {
/*val currentGame: GameVersion by lazy {
    SharedConstants.getCurrentVersion().let { GameVersion(it.name(), it.dataVersion().version()) }
}
*///?} else {
val currentGame: GameVersion by lazy {
    SharedConstants.getCurrentVersion().let { GameVersion(it.name, it.dataVersion.version) }
}
//?}

/** Show [screen] (null closes the current one). 26.x moved the screen to `minecraft.gui`. */
//? if >=26.2 {
/*fun Minecraft.openScreen(screen: Screen?) = gui.setScreen(screen)
val Minecraft.currentScreen: Screen? get() = gui.screen()
val Minecraft.hasOverlay: Boolean get() = gui.overlay() != null
*///?} else {
fun Minecraft.openScreen(screen: Screen?) = setScreen(screen)
val Minecraft.currentScreen: Screen? get() = screen
val Minecraft.hasOverlay: Boolean get() = overlay != null
//?}

/** Draw [text] at ([x], [y]) with a shadow. */
//? if >=26.2 {
/*fun GuiGraphics.jukzText(font: net.minecraft.client.gui.Font, text: String, x: Int, y: Int, color: Int) = text(font, text, x, y, color)
*///?} else {
fun GuiGraphics.jukzText(font: net.minecraft.client.gui.Font, text: String, x: Int, y: Int, color: Int) { drawString(font, text, x, y, color) }
//?}

/** The widgets (buttons) of [screen], to add jukz's own next to vanilla's. */
//? if >=26.2 {
/*fun screenWidgets(screen: Screen): MutableList<net.minecraft.client.gui.components.AbstractWidget> =
    net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen)
*///?} else {
fun screenWidgets(screen: Screen): MutableList<net.minecraft.client.gui.components.AbstractWidget> =
    net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen)
//?}
