package dev.jukz.client.gui

import dev.jukz.client.GuestSession
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

/**
 * A transient wait shown to a guest the instant its host's connection drops, in place of the vanilla
 * "Connection lost" screen: the host may be handing the world off over the live control channel, and
 * the takeover prompt ([HostHandoffScreen]) replaces this a moment later. The Cancel button is a
 * safety net for the rare case where no handoff materialises (e.g. a relay blip), so the player is
 * never stuck on the spinner.
 */
class HostLeavingScreen : JukzStatusScreen(
    Component.literal("The host left"),
    Component.literal("Getting the world…"),
    accentColor = ACCENT_INFO,
) {
    override fun buttons() = listOf(
        StatusButton("Cancel") {
            GuestSession.leave()
            Minecraft.getInstance().setScreen(TitleScreen())
        },
    )
}
