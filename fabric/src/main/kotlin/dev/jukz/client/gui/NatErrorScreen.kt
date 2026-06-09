package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Shown when the join fails (transport / NAT / handshake); offers retry or local hosting. */
class NatErrorScreen(
    detail: String,
    private val onRetry: () -> Unit,
    private val onHostLocally: () -> Unit,
) : JukzStatusScreen(
    Text.literal("Couldn't connect"),
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
            ButtonWidget.builder(Text.literal("Host locally")) { onHostLocally() }
                .dimensions(cx + 4, y, 150, 20).build(),
        )
    }

    override fun shouldCloseOnEsc(): Boolean = true
}
