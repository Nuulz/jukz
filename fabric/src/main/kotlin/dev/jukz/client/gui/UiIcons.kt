package dev.jukz.client.gui

import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import dev.jukz.cosmetics.Cosmetics
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Small ASCII-art icons for jukz's own buttons (drawn like the badges). */
object UiIcons {
    /** A Ko-fi cup with a heart, steaming. */
    val KOFI: CosmeticCatalog.Art = CosmeticCatalog.art(
        listOf(
            "................",
            "....s...s.......",
            ".....s...s......",
            "....s...s.......",
            "................",
            ".oooooooooooo...",
            ".owwwwwwwwwwoooo",
            ".owwrrwwrrwwo..o",
            ".owrrrrrrrrwo..o",
            ".owrrrrrrrrwo..o",
            ".owwrrrrrrwwoooo",
            ".owwwrrrrwwwo...",
            "..owwwrrwwwo....",
            "...owwwwwwo.....",
            "....oooooo......",
            "................",
        ),
        mapOf(
            'o' to 0xFF0B1A33.toInt(),
            'w' to 0xFFFFFFFF.toInt(),
            'r' to 0xFFFF5E5B.toInt(),
            's' to 0xFFB8C8E0.toInt(),
        ),
    )

    /** The default badge (the jukz world cube), for the cosmetics button. */
    fun jukz(): CosmeticCatalog.Art? = Cosmetics.catalog.item(Cosmetics.catalog.defaultBadge)?.art
}

/** A vanilla-looking 20x20 button showing an ASCII-art icon instead of text. */
class IconButton(
    x: Int,
    y: Int,
    private val icon: () -> CosmeticCatalog.Art?,
    tooltip: Text,
    onPress: PressAction,
) : ButtonWidget(x, y, SIZE, SIZE, Text.empty(), onPress, DEFAULT_NARRATION_SUPPLIER) {

    init {
        setTooltip(Tooltip.of(tooltip))
        message = tooltip // narration reads it; the text itself isn't drawn (see renderWidget)
    }

    override fun renderWidget(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val label = message
        message = Text.empty()
        super.renderWidget(context, mouseX, mouseY, delta) // the vanilla button frame, without its label
        message = label
        icon()?.let { BadgeRenderer.draw(context, it, x + 2, y + 2, SIZE - 4) }
    }

    companion object {
        const val SIZE = 20
    }
}
