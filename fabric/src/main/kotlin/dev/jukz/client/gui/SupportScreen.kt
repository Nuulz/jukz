package dev.jukz.client.gui

import dev.jukz.compat.openScreen
import dev.jukz.config.JukzState
import dev.jukz.compat.BaseUIComponent
import io.wispforest.owo.ui.container.FlowLayout
import dev.jukz.compat.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * The welcome + Ko-fi note: shown once, on the first launch of a fresh install (updates get
 * [UpdateScreen] instead; see [JukzState]). It asks, explains why, and says plainly that
 * supporting unlocks nothing.
 */
class SupportScreen(private val parent: Screen?) : JukzStatusScreen(
    heading = Component.literal("Thanks for installing jukz!"),
    statusLine = Component.literal(
        "jukz is free, and it stays free. Finding worlds, the relay and the cloud backups run on a " +
            "server I pay for myself. If jukz kept your world alive, a coffee on Ko-fi keeps it running " +
            "for everyone. Supporting doesn't unlock anything: every feature is the same for all."
    ),
    accentColor = ACCENT_ACTION,
    showSpinner = false,
) {
    init {
        JukzState.markVersionSeen() // once per version, even if the game is closed on this screen
    }

    override fun buttons() = listOf(
        StatusButton("Support on Ko-fi", 130) { ConfirmLinkScreen.confirmLinkNow(parent, CosmeticsScreen.KOFI_URL) },
        StatusButton("Maybe later", 100) { minecraft?.openScreen(parent) },
    )

    override fun build(root: FlowLayout) {
        super.build(root)
        root.child(0, CupIcon()) // above the panel, outside it
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun onClose() {
        minecraft?.openScreen(parent)
    }

    private class CupIcon : BaseUIComponent() {
        init {
            sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            dev.jukz.cosmetics.BadgeRenderer.draw(context, UiIcons.KOFI, x, y, SIZE)
        }

        companion object {
            const val SIZE = 32
        }
    }
}
