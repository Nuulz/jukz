package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
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
    override fun init() {
        val cx = width / 2
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Cancel")) { onCancel() }
                .dimensions(cx - 75, height / 2 + 28, 150, 20).build(),
        )
    }
}
