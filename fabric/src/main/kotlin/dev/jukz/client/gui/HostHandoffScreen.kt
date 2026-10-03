package dev.jukz.client.gui

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
    override fun buttons() = listOf(
        StatusButton("Host now") { onHostNow() },
        StatusButton("Back") { onBack() },
    )

    override fun shouldCloseOnEsc(): Boolean = true

    override fun close() {
        onBack()
    }

    companion object {
        const val TITLE = "The host left"

        fun message(snapshotApplied: Boolean): String =
            if (snapshotApplied) {
                "The host left. You have the latest world — host now to keep it online for everyone."
            } else {
                "The host left unexpectedly. Host from your local copy to keep the world online."
            }
    }
}
