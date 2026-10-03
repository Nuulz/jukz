package dev.jukz.client.gui

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
    override fun buttons() = listOf(
        StatusButton("Retry") { onRetry() },
        StatusButton("Host locally") { onHostLocally() },
    )

    override fun shouldCloseOnEsc(): Boolean = true
}
