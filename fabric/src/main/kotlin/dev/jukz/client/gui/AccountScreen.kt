package dev.jukz.client.gui

import net.minecraft.client.gui.screens.Screen

/** Your jukz account: the hub, opened on Profile. Kept so every entry point (title screen, Mod Menu) stays put. */
class AccountScreen(parent: Screen?) : HubScreen(parent, Section.PROFILE)
