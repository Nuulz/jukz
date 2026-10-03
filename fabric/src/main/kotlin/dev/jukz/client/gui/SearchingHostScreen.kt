package dev.jukz.client.gui

import net.minecraft.text.Text

/** Shown while the mod queries discovery for a live host of a world. Cancellable. */
class SearchingHostScreen(
    shortCode: String,
    private val onCancel: () -> Unit,
) : JukzStatusScreen(
    Text.literal("Looking for a host"),
    Text.literal("Searching the network for $shortCode"),
    accentColor = ACCENT_INFO,
) {
    override fun buttons() = listOf(StatusButton("Cancel") { onCancel() })
}
