package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Shown when NAT traversal fails; offers retry or falling back to local hosting. */
class NatErrorScreen(
    detail: String,
    private val onRetry: () -> Unit,
    private val onHostLocally: () -> Unit,
) : JukzStatusScreen(Text.literal("jukz — connection failed"), Text.literal(detail)) {

    override fun init() {
        val cx = width / 2
        val y = height / 2 + 20
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Retry")) { onRetry() }
                .dimensions(cx - 154, y, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Host locally")) { onHostLocally() }
                .dimensions(cx + 4, y, 150, 20).build(),
        )
    }
}
