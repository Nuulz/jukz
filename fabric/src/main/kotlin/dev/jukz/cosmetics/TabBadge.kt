package dev.jukz.cosmetics

import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.StringVisitable
import net.minecraft.text.Style
import net.minecraft.text.Text
import java.util.Optional
import java.util.UUID

/** The tab-list half of badges, called from PlayerListHudMixin. */
object TabBadge {
    /** Marks the reserved gap; the badge id follows it. Insertion is shift-click text, unused in the tab. */
    private const val MARKER = "jukz:badge:"

    /** Two spaces of the default font: an 8 px gap, the badge's size. */
    private const val GAP = "  "
    const val SIZE = 8

    @JvmStatic
    fun decorate(name: Text, player: UUID): Text {
        val badge = Cosmetics.badgeFor(player) ?: return name
        // Badge first, then a space, then the name as vanilla built it.
        return Text.empty()
            .append(Text.literal(GAP).setStyle(Style.EMPTY.withInsertion(MARKER + badge.id)))
            .append(Text.literal(" "))
            .append(name)
    }

    @JvmStatic
    fun draw(context: DrawContext, renderer: TextRenderer, text: Text, x: Int, y: Int) {
        var offset = 0
        val id = text.visit(StringVisitable.StyledVisitor { style, segment ->
            val insertion = style.insertion
            if (insertion != null && insertion.startsWith(MARKER)) {
                Optional.of(insertion.removePrefix(MARKER))
            } else {
                offset += renderer.getWidth(Text.literal(segment).setStyle(style))
                Optional.empty()
            }
        }, Style.EMPTY).orElse(null) ?: return
        val badge = Cosmetics.catalog.item(id) ?: return
        BadgeRenderer.draw(context, badge, x + offset, y, SIZE)
    }
}
