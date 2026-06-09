package dev.jukz.client.gui

import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Shows the world's shareable short code with a copy-to-clipboard button. */
class ShareWorldScreen(
    private val shortCode: String,
    private val parent: Screen?,
) : JukzStatusScreen(Text.literal("jukz — share this world"), Text.literal(shortCode)) {

    override fun init() {
        val cx = width / 2
        val y = height / 2 + 20
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Copy code")) {
                client?.keyboard?.clipboard = shortCode
            }.dimensions(cx - 154, y, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Done")) {
                client?.setScreen(parent)
            }.dimensions(cx + 4, y, 150, 20).build(),
        )
    }
}
