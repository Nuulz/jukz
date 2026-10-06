package dev.jukz.client.gui

import net.minecraft.client.gui.screens.Screen

/** Dress up: the hub, opened on Cosmetics. Kept so every entry point (title, Multiplayer, pause) stays put. */
class CosmeticsScreen(parent: Screen?) : HubScreen(parent, Section.COSMETICS) {
    companion object {
        const val KOFI_URL = HubScreen.KOFI_URL
    }
}
