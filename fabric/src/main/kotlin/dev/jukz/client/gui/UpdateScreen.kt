package dev.jukz.client.gui

import dev.jukz.config.Changelog
import dev.jukz.config.JukzState
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.FlowLayout
import net.minecraft.client.gui.screen.ConfirmLinkScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * "jukz was updated": shown once, on the first launch of a new version (see [JukzState]). Lists the
 * changelog bullets of every version since the one that ran last, links the full notes on GitHub, and
 * mentions Ko-fi in one quiet line.
 *
 * Layout: `assets/jukz/owo_ui/update.xml` (style from `theme.xml`).
 */
class UpdateScreen(private val parent: Screen?, private val previous: String?) : JukzUiScreen("update") {

    init {
        JukzState.markVersionSeen() // once per version, even if the game is closed on this screen
    }

    override fun build(root: FlowLayout) {
        val version = JukzState.modVersion
        accent(root, COLOR_LIVE)
        label(root, "title").text(Text.literal("jukz was updated to $version"))
        label(root, "subtitle").text(Text.literal(if (previous != null) "What's new since $previous:" else "What's new:"))

        val sections = Changelog.between(Changelog.bundled(), previous, version)
        val changes = root.childById(FlowLayout::class.java, "changes")
        val bullets = sections.flatMap { it.bullets }.ifEmpty { listOf("Fixes and improvements. The full notes are on GitHub.") }
        bullets.forEachIndexed { i, text ->
            val row = ui!!.expandTemplate(FlowLayout::class.java, "change", mapOf("id" to "change-$i"))
            row.childById(LabelComponent::class.java, "change-$i").text(Text.literal(text))
            changes.child(row)
        }

        label(root, "hint").text(Text.literal("jukz stays free. If you like it, Ko-fi helps keep the servers on."))
        addButton(root, "buttons", Text.literal("Release notes"), width = 100) {
            ConfirmLinkScreen.open(this, "$RELEASES/tag/v$version")
        }
        addButton(root, "buttons", Text.literal("Ko-fi"), width = 60) { ConfirmLinkScreen.open(this, CosmeticsScreen.KOFI_URL) }
        addButton(root, "buttons", Text.literal("Continue"), width = 100) { client?.setScreen(parent) }
    }

    override fun shouldCloseOnEsc(): Boolean = true

    override fun close() {
        client?.setScreen(parent)
    }

    companion object {
        private const val RELEASES = "https://github.com/Nuulz/jukz/releases"
    }
}
