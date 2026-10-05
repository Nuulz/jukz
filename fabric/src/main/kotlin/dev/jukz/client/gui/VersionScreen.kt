package dev.jukz.client.gui

import dev.jukz.compat.currentGame
import dev.jukz.core.model.GameVersion
import net.minecraft.network.chat.Component

/**
 * A world and this game are on different Minecraft versions. Worlds only move forward: a newer game
 * can open an older world (which upgrades it, so friends still on the old version lose it), never the
 * other way round, and players on different versions can't join each other. [onContinue] (when given)
 * goes ahead with the upgrade; without it the screen only explains.
 */
class VersionScreen(
    title: String,
    detail: String,
    private val onBack: () -> Unit,
    private val continueLabel: String? = null,
    private val onContinue: (() -> Unit)? = null,
) : JukzStatusScreen(
    Component.literal(title),
    Component.literal(detail),
    accentColor = if (onContinue != null) ACCENT_ACTION else ACCENT_ERROR,
    showSpinner = false,
) {
    override fun buttons() = listOfNotNull(
        onContinue?.let { go -> StatusButton(continueLabel ?: "Continue", 170) { go() } },
        StatusButton("Back", 100) { onBack() },
    )

    override fun shouldCloseOnEsc(): Boolean = true

    companion object {
        private val mine get() = currentGame.name

        /** Someone is hosting the world on another version. */
        fun hostOnOtherVersion(host: GameVersion, onBack: () -> Unit) = VersionScreen(
            "This world is being played on ${host.name}",
            "The host runs Minecraft ${host.name} and you're on $mine, so you can't join each other. " +
                "Play it with Minecraft ${host.name}, or wait until they move it to $mine.",
            onBack,
        )

        /** The latest copy (cloud or local) is from a newer version than this game. */
        fun tooNew(saved: GameVersion, onBack: () -> Unit) = VersionScreen(
            "This world is from ${saved.name}",
            "Its latest copy was saved on Minecraft ${saved.name}, newer than your $mine. " +
                "Worlds can't go back to an older version: open it with Minecraft ${saved.name}.",
            onBack,
        )

        /** Opening an older world here upgrades it; ask first. */
        fun upgrade(saved: GameVersion, onBack: () -> Unit, onUpgrade: () -> Unit) = VersionScreen(
            "Update this world to $mine?",
            "It was last played on Minecraft ${saved.name}. Opening it here updates it to $mine for good, " +
                "and friends still on ${saved.name} won't be able to open it or join you anymore.",
            onBack,
            "Update and play",
            onUpgrade,
        )
    }
}
