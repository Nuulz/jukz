package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Shown while establishing the transport to a host. [natPath] is the chosen traversal tier. */
class ConnectingScreen(
    natPath: String,
    private val onCancel: () -> Unit,
) : JukzStatusScreen(
    Text.literal("Connecting to host"),
    Text.literal("Negotiating a path ($natPath)"),
    accentColor = ACCENT_CONNECT,
) {
    override fun init() {
        val cx = width / 2
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Cancel")) { onCancel() }
                .dimensions(cx - 75, height / 2 + 28, 150, 20).build(),
        )
    }
}
