package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Shown to a guest when the host closed access to the world (F4-D). The host keeps playing privately,
 * so unlike [HostHandoffScreen] there is deliberately no "Host now": this guest's local copy is stale,
 * and hosting it would put a second, diverging copy of the world online.
 */
class AccessClosedScreen(
    private val onBack: () -> Unit,
) : JukzStatusScreen(
    Text.literal("The host closed access"),
    Text.literal("The world is private for now. Join again once it reopens."),
    accentColor = ACCENT_ERROR,
    showSpinner = false,
) {
    override fun init() {
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) { onBack() }
                .dimensions(width / 2 - 75, height / 2 + 28, 150, 20).build(),
        )
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun close() {
        onBack()
    }
}
