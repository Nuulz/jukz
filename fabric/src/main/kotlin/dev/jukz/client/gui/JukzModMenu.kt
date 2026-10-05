package dev.jukz.client.gui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu's Config button for jukz opens the account screen (plan, cloud worlds, upload). Mod Menu stays optional. */
class JukzModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent -> AccountScreen(parent) }
}
