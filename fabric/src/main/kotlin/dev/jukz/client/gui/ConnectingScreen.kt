package dev.jukz.client.gui

import net.minecraft.network.chat.Component

/** Shown while establishing the transport to a host. [natPath] is the chosen traversal tier. */
class ConnectingScreen(
    natPath: String,
    private val onCancel: () -> Unit,
) : JukzStatusScreen(
    Component.literal("Connecting to host"),
    Component.literal("Negotiating a path ($natPath)"),
    accentColor = ACCENT_CONNECT,
) {
    override fun buttons() = listOf(StatusButton("Cancel") { onCancel() })
}
