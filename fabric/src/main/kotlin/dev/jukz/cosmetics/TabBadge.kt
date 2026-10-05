package dev.jukz.cosmetics

import net.minecraft.client.gui.Font
import dev.jukz.compat.GuiGraphics
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import java.util.Optional
import java.util.UUID

/** The tab-list half of badges, called from PlayerTabOverlayMixin. */
object TabBadge {
    /** Marks the reserved gap; the badge id follows it. Insertion is shift-click text, unused in the tab. */
    private const val MARKER = "jukz:badge:"

    /** Two spaces of the default font: an 8 px gap, the badge's size. */
    private const val GAP = "  "
    const val SIZE = 8

    @JvmStatic
    fun decorate(name: Component, player: UUID): Component {
        val badge = Cosmetics.badgeFor(player) ?: return name
        // Badge first, then a space, then the name as vanilla built it.
        return Component.empty()
            .append(Component.literal(GAP).setStyle(Style.EMPTY.withInsertion(MARKER + badge.id)))
            .append(Component.literal(" "))
            .append(name)
    }

    @JvmStatic
    fun draw(context: GuiGraphics, renderer: Font, text: Component, x: Int, y: Int) {
        var offset = 0
        val id = text.visit(FormattedText.StyledContentConsumer { style, segment ->
            val insertion = style.insertion
            if (insertion != null && insertion.startsWith(MARKER)) {
                Optional.of(insertion.removePrefix(MARKER))
            } else {
                offset += renderer.width(Component.literal(segment).setStyle(style))
                Optional.empty()
            }
        }, Style.EMPTY).orElse(null) ?: return
        val badge = Cosmetics.catalog.item(id) ?: return
        BadgeRenderer.draw(context, badge, x + offset, y, SIZE)
    }
}
