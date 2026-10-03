package dev.jukz.client.gui

import dev.jukz.client.JoinCoordinator
import dev.jukz.core.model.WorldId
import io.wispforest.owo.ui.component.TextBoxComponent
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.core.Component
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/** Prompts for a world short code, validates it, and starts the host search (model `join_prompt.xml`). */
class JoinPromptScreen(private val parent: Screen?) : JukzUiScreen("join_prompt") {

    private var codeBox: TextBoxComponent? = null
    private var code = "" // survives a hot-reload rebuild

    override fun build(root: FlowLayout) {
        label(root, "title").text(Text.literal("Play together"))
        label(root, "message").text(Text.literal("Paste the world code a friend shared with you."))
        codeBox = root.childById(TextBoxComponent::class.java, "code").apply {
            setPlaceholder(Text.literal("JUKZ-XXXX-XXXX-…"))
            text(code)
            onChanged().subscribe { code = it }
        }
        addButton(root, "buttons", Text.literal("Join")) { join(root) }
        addButton(root, "buttons", Text.literal("Back")) { client?.setScreen(parent) }
    }

    override fun init() {
        super.init()
        // Ready to type (or paste) straight away.
        codeBox?.let { uiAdapter?.rootComponent?.focusHandler()?.focus(it, Component.FocusSource.KEYBOARD_CYCLE) }
    }

    private fun join(root: FlowLayout) {
        val parsed = runCatching { WorldId.fromShortCode(code) }.getOrNull()
        if (parsed != null) {
            JoinCoordinator.start(parsed, parsed.shortCode(), parent)
        } else {
            label(root, "error").text(Text.literal("Invalid world code"))
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            uiAdapter?.rootComponent?.let { join(it) }
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }
}
