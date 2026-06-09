package dev.jukz.client.gui

import dev.jukz.client.JoinCoordinator
import dev.jukz.core.model.WorldId
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

/** Prompts for a world short code, validates it, and starts the host search. */
class JoinPromptScreen(private val parent: Screen?) : Screen(Text.literal("Join via jukz")) {

    private lateinit var codeField: TextFieldWidget
    private var error: Text? = null

    override fun init() {
        val cx = width / 2
        codeField = TextFieldWidget(textRenderer, cx - 150, height / 2 - 10, 300, 20, Text.literal("World code"))
        codeField.setMaxLength(64)
        codeField.setPlaceholder(Text.literal("JUKZ-XXXX-XXXX-…"))
        addSelectableChild(codeField)
        addDrawableChild(codeField)

        addDrawableChild(
            ButtonWidget.builder(Text.literal("Join")) {
                val parsed = runCatching { WorldId.fromShortCode(codeField.text) }.getOrNull()
                if (parsed != null) {
                    JoinCoordinator.start(parsed, parsed.shortCode(), parent)
                } else {
                    error = Text.literal("Invalid world code")
                }
            }.dimensions(cx - 154, height / 2 + 20, 150, 20).build(),
        )
        addDrawableChild(
            ButtonWidget.builder(Text.literal("Back")) {
                client?.setScreen(parent)
            }.dimensions(cx + 4, height / 2 + 20, 150, 20).build(),
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 3, 0xFFFFFF)
        error?.let { context.drawCenteredTextWithShadow(textRenderer, it, width / 2, height / 2 + 48, 0xFF5555) }
    }
}
