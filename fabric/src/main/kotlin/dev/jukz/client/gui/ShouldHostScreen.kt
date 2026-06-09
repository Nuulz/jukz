package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Shown when nobody is hosting the world (no record, or the announced host turned out to be a
 * ghost). Offers the player to open it locally — becoming the host — or to go back.
 */
class ShouldHostScreen(
    detail: String,
    private val onBack: () -> Unit,
    /** Only offered when the player actually owns this world locally (the mixin flow). */
    private val onHostLocally: (() -> Unit)? = null,
) : JukzStatusScreen(
    Text.literal("Nobody is hosting this world"),
    Text.literal(detail),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    override fun init() {
        val cx = width / 2
        val y = height / 2 + 8
        val host = onHostLocally
        if (host != null) {
            addDrawableChild(
                ButtonWidget.builder(Text.literal("Open it yourself")) { host() }
                    .dimensions(cx - 154, y, 150, 20).build(),
            )
            addDrawableChild(
                ButtonWidget.builder(Text.literal("Back")) { onBack() }
                    .dimensions(cx + 4, y, 150, 20).build(),
            )
        } else {
            addDrawableChild(
                ButtonWidget.builder(Text.literal("Back")) { onBack() }
                    .dimensions(cx - 75, y, 150, 20).build(),
            )
        }
    }

    override fun shouldCloseOnEsc(): Boolean = true
}
