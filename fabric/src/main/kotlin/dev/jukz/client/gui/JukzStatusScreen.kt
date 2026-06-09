package dev.jukz.client.gui

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/** Shared layout for jukz status overlays: a centered title and a status line. */
abstract class JukzStatusScreen(title: Text, private val statusLine: Text) : Screen(title) {

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 3, 0xFFFFFF)
        context.drawCenteredTextWithShadow(textRenderer, statusLine, width / 2, height / 3 + 18, 0xA0A0A0)
    }

    override fun shouldCloseOnEsc(): Boolean = false
}
