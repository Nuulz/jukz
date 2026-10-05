package dev.jukz.client.gui

import net.minecraft.network.chat.Component

/** Shown while the mod queries discovery for a live host of a world. Cancellable. */
class SearchingHostScreen(
    shortCode: String,
    private val onCancel: () -> Unit,
) : JukzStatusScreen(
    Component.literal("Looking for a host"),
    Component.literal("Searching the network for $shortCode"),
    accentColor = ACCENT_INFO,
) {
    override fun buttons() = listOf(StatusButton("Cancel") { onCancel() })
}
