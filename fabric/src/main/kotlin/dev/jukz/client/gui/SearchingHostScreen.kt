package dev.jukz.client.gui

import net.minecraft.text.Text

/** Shown while the mod queries the DHT for a live host of a world. */
class SearchingHostScreen(shortCode: String) : JukzStatusScreen(
    Text.literal("jukz"),
    Text.literal("Searching for a live host of $shortCode…"),
)
