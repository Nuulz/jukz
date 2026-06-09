package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Shown when opening the world to jukz fails, or the world is already hosted elsewhere. */
class HostErrorScreen(
    detail: String,
    private val onRetry: () -> Unit,
    private val onBack: () -> Unit,
) : JukzStatusScreen(
    Text.literal("Couldn't share this world"),
    Text.literal(detail),
    accentColor = ACCENT_ERROR,
    showSpinner = false,
) {
    override fun init() {
        val cx = width / 2
        val y = height / 2 + 8
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Retry")) { onRetry() }
                .dimensions(cx - 154, y, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) { onBack() }
                .dimensions(cx + 4, y, 150, 20).build(),
        )
    }

    override fun shouldCloseOnEsc(): Boolean = true
}
