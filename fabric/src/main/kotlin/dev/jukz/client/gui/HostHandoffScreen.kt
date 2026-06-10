package dev.jukz.client.gui

import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Shown when the announced host turned out to be gone during a join, after we tried to pull its last
 * snapshot (F4-B). If the snapshot applied, the guest now holds the latest copy and is offered to
 * take over hosting; if it did not, the guest can still host from its own local copy. Either way the
 * world stays alive on whoever clicks "Host now".
 */
class HostHandoffScreen(
    snapshotApplied: Boolean,
    private val onHostNow: () -> Unit,
    private val onBack: () -> Unit,
) : JukzStatusScreen(
    Text.literal(TITLE),
    Text.literal(message(snapshotApplied)),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    override fun init() {
        val cx = width / 2
        val y = height / 2 + 8
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Host now")) { onHostNow() }
                .dimensions(cx - 154, y, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) { onBack() }
                .dimensions(cx + 4, y, 150, 20).build(),
        )
    }

    override fun shouldCloseOnEsc(): Boolean = true

    companion object {
        const val TITLE = "The host disconnected"

        /** The status line, split out so it is unit-testable without standing up a Screen. */
        fun message(snapshotApplied: Boolean): String =
            if (snapshotApplied) {
                "The host disconnected. You have the last known copy — host now?"
            } else {
                "Host disconnected unexpectedly. Continuing from your local copy."
            }
    }
}
