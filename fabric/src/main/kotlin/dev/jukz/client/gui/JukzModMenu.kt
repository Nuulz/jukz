package dev.jukz.client.gui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu's Config button for jukz opens the hub on Settings (the rest is a click away). Mod Menu stays optional. */
class JukzModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent -> HubScreen(parent, HubScreen.Section.SETTINGS) }
}
