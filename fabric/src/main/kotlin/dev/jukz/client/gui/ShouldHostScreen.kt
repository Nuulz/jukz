package dev.jukz.client.gui

import net.minecraft.text.Text

/**
 * Shown when nobody is hosting the world (no record, or the announced host turned out to be a
 * ghost). Offers the player to open it locally — becoming the host — or to go back.
 */
class ShouldHostScreen(
    detail: String,
    private val onBack: () -> Unit,
    private val onHostLocally: (() -> Unit)? = null,
) : JukzStatusScreen(
    Text.literal("Nobody is hosting this world"),
    Text.literal(detail),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    override fun buttons() = listOfNotNull(
        onHostLocally?.let { host -> StatusButton("Open it yourself") { host() } },
        StatusButton("Back") { onBack() },
    )

    override fun shouldCloseOnEsc(): Boolean = true
}
