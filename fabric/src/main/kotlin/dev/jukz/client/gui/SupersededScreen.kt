package dev.jukz.client.gui

import net.minecraft.network.chat.Component

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
    Component.literal("This world is already live elsewhere"),
    Component.literal("Another player is hosting $shortCode right now. Playing this copy will diverge from theirs."),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    override fun buttons() = listOf(
        StatusButton("Join the live host") { onJoinInstead() },
        StatusButton("Keep playing locally") { onKeepPlaying() },
    )

    override fun shouldCloseOnEsc(): Boolean = true

    override fun onClose() {
        onKeepPlaying() // Esc means "keep playing locally"
    }
}
