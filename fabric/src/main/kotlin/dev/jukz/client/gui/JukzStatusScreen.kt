package dev.jukz.client.gui

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Shared layout for every jukz status overlay. Renders the standard menu background, a "jukz"
 * brand header in the screen's accent colour, a primary title, a status line, and — while work is
 * in flight — an animated indeterminate progress bar. The moving bar is the cue that the mod is
 * working rather than frozen, so a slow lookup never reads as a crash.
 */
abstract class JukzStatusScreen(
    title: Text,
    private val statusLine: Text,
    private val accentColor: Int = ACCENT_INFO,
    private val showSpinner: Boolean = true,
) : Screen(title) {

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta) // background + widgets

        val cx = width / 2
        context.drawCenteredTextWithShadow(textRenderer, BRAND, cx, height / 3 - 26, accentColor)
        context.drawCenteredTextWithShadow(textRenderer, title, cx, height / 3, COLOR_TITLE)
        context.drawCenteredTextWithShadow(textRenderer, statusLine, cx, height / 3 + 16, COLOR_SUBTLE)

        if (showSpinner) drawIndeterminateBar(context)
    }

    /** A 1.5s ping-pong segment sliding inside a track — a texture-free indeterminate spinner. */
    private fun drawIndeterminateBar(context: DrawContext) {
        val barW = 160
        val barH = 3
        val bx = (width - barW) / 2
        val by = height / 2 + 4
        context.fill(bx, by, bx + barW, by + barH, COLOR_TRACK)

        val period = 1500.0
        val t = (System.currentTimeMillis() % period.toLong()) / period
        val pingPong = if (t < 0.5) t * 2 else 2 - t * 2 // 0 -> 1 -> 0
        val segW = 46
        val sx = bx + ((barW - segW) * pingPong).toInt()
        context.fill(sx, by, sx + segW, by + barH, accentColor)
    }

    override fun shouldCloseOnEsc(): Boolean = false

    companion object {
        val BRAND: Text = Text.literal("jukz")

        // Colours are full ARGB; a missing alpha byte renders transparent on 1.21.1.
        const val COLOR_TITLE = 0xFFFFFFFF.toInt()
        const val COLOR_SUBTLE = 0xFFB0B0B0.toInt()
        const val COLOR_TRACK = 0xFF2B2B2B.toInt()

        const val ACCENT_INFO = 0xFF7FB2FF.toInt() // searching / working
        const val ACCENT_CONNECT = 0xFF5B9BFF.toInt() // connecting
        const val ACCENT_ERROR = 0xFFFF6B6B.toInt() // failure
        const val ACCENT_ACTION = 0xFFFFC24A.toInt() // call to action (should-host)
    }
}
