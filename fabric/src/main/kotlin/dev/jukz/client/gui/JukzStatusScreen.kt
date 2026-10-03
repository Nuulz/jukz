package dev.jukz.client.gui

import io.wispforest.owo.ui.container.FlowLayout
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Shared layout for every jukz status overlay (model `status.xml`): the "jukz" brand in the screen's
 * accent colour, a title, a wrapped message, and — while work is in flight — an animated
 * indeterminate bar, so a slow lookup never reads as a crash. Subclasses only declare their [buttons].
 */
abstract class JukzStatusScreen(
    private val heading: Text,
    private val statusLine: Text,
    private val accentColor: Int = ACCENT_INFO,
    private val showSpinner: Boolean = true,
) : JukzUiScreen("status") {

    /** One button under the message. */
    protected class StatusButton(val text: String, val width: Int = 150, val onPress: () -> Unit)

    /** The buttons under the message, left to right (none by default). */
    protected open fun buttons(): List<StatusButton> = emptyList()

    private var bar: ProgressBar? = null

    override fun build(root: FlowLayout) {
        accent(root, accentColor)
        label(root, "title").text(heading)
        label(root, "message").text(statusLine)
        bar = progressBar(root, "bar")
        if (!showSpinner) {
            bar?.let { detach(root, it.track) }
            bar = null
        }
        val buttons = buttons()
        if (buttons.isEmpty()) detach(root, root.childById(FlowLayout::class.java, "buttons"))
        buttons.forEach { addButton(root, "buttons", Text.literal(it.text), it.width, it.onPress) }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        bar?.indeterminate(accentColor) // before drawing, so this frame shows the new position
        super.render(context, mouseX, mouseY, delta)
    }

    override fun shouldCloseOnEsc(): Boolean = false
}
