package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Shown when this world opened locally but the announce was rejected: another host already owns
 * the world's live record (decision 4: never silent). The player chooses — keep playing this local
 * copy knowing it will diverge from the live one, or leave it and join the live host as a guest.
 */
class SupersededScreen(
    shortCode: String,
    private val onKeepPlaying: () -> Unit,
    private val onJoinInstead: () -> Unit,
) : JukzStatusScreen(
    Text.literal("This world is already live elsewhere"),
    Text.literal("Another player is hosting $shortCode right now. Playing this copy will diverge from theirs."),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    override fun init() {
        val cx = width / 2
        val y = height / 2 + 8
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Join the live host")) { onJoinInstead() }
                .dimensions(cx - 154, y, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Keep playing locally")) { onKeepPlaying() }
                .dimensions(cx + 4, y, 150, 20).build(),
        )
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun close() {
        onKeepPlaying() // Esc means "keep playing locally"
    }
}
