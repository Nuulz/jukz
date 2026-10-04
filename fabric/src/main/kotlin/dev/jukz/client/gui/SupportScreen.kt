package dev.jukz.client.gui

import dev.jukz.config.JukzState
import io.wispforest.owo.ui.base.BaseComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.OwoUIDrawContext
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.gui.screen.ConfirmLinkScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * The welcome + Ko-fi note: shown once, on the first launch of a fresh install (updates get
 * [UpdateScreen] instead; see [JukzState]). It asks, explains why, and says plainly that
 * supporting unlocks nothing.
 */
class SupportScreen(private val parent: Screen?) : JukzStatusScreen(
    heading = Text.literal("Thanks for installing jukz!"),
    statusLine = Text.literal(
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
        StatusButton("Support on Ko-fi", 130) { ConfirmLinkScreen.open(parent, CosmeticsScreen.KOFI_URL) },
        StatusButton("Maybe later", 100) { client?.setScreen(parent) },
    )

    override fun build(root: FlowLayout) {
        super.build(root)
        root.child(0, CupIcon()) // above the panel, outside it
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun close() {
        client?.setScreen(parent)
    }

    private class CupIcon : BaseComponent() {
        init {
            sizing(Sizing.fixed(SIZE), Sizing.fixed(SIZE))
        }

        override fun draw(context: OwoUIDrawContext, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            dev.jukz.cosmetics.BadgeRenderer.draw(context, UiIcons.KOFI, x, y, SIZE)
        }

        companion object {
            const val SIZE = 32
        }
    }
}
