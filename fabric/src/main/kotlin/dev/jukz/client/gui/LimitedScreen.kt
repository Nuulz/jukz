package dev.jukz.client.gui

import net.minecraft.network.chat.Component

/** Shown when the rendezvous refuses a join: too many worlds opened or joined in a short time. */
class LimitedScreen(
    retryAfterSecs: Long,
    private val onBack: () -> Unit,
) : JukzStatusScreen(
    Component.literal("Too many worlds in a short time"),
    Component.literal("To keep jukz safe from bots, each PC can open or join a few worlds per minute. Try again in ${waitText(retryAfterSecs)}."),
    accentColor = ACCENT_ERROR,
    showSpinner = false,
) {
    override fun buttons() = listOf(StatusButton("Back") { onBack() })

    override fun shouldCloseOnEsc(): Boolean = true

    companion object {
        fun waitText(secs: Long): String = when {
            secs < 60 -> "$secs s"
            secs < 3600 -> "${(secs + 59) / 60} min"
            else -> "${(secs + 3599) / 3600} h"
        }
    }
}
