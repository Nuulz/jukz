package dev.jukz.client.gui

import net.minecraft.network.chat.Component

/**
 * Shown to a guest when the host closed access to the world (F4-D). The host keeps playing privately,
 * so unlike [HostHandoffScreen] there is deliberately no "Host now": this guest's local copy is stale,
 * and hosting it would put a second, diverging copy of the world online.
 */
class AccessClosedScreen(
    private val onBack: () -> Unit,
) : JukzStatusScreen(
    Component.literal("The host closed access"),
    Component.literal("The world is private for now. Join again once it reopens."),
    accentColor = ACCENT_ERROR,
    showSpinner = false,
) {
    override fun buttons() = listOf(StatusButton("Back") { onBack() })

    override fun shouldCloseOnEsc(): Boolean = true

    override fun onClose() {
        onBack()
    }
}
