package dev.jukz.client.gui

import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import dev.jukz.cosmetics.Cosmetics
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

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

    /** A player bust in a jukz-blue frame: your account. */
    val ACCOUNT: CosmeticCatalog.Art = CosmeticCatalog.art(
        listOf(
            "................",
            ".....oooooo.....",
            "....osssssso....",
            "...osssssssso...",
            "...oskksskkso...",
            "...osWksskWso...",
            "...osssssssso...",
            "....ossppsso....",
            ".....oooooo.....",
            "...oobbbbbboo...",
            "..obbbbbbbbbbo..",
            ".obbbbbwwbbbbbo.",
            ".obbbbbwwbbbbbo.",
            ".obbbbbbbbbbbbo.",
            ".oooooooooooooo.",
            "................",
        ),
        mapOf(
            'o' to 0xFF0B1A33.toInt(),
            's' to 0xFFF2C9A0.toInt(),
            'k' to 0xFF3A2A20.toInt(),
            'W' to 0xFFFFFFFF.toInt(),
            'b' to 0xFF5B9BFF.toInt(),
            'w' to 0xFFD6E7FF.toInt(),
            'p' to 0xFFC98A70.toInt(),
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
    tooltip: Component,
    onPress: Button.OnPress,
) : Button(x, y, SIZE, SIZE, Component.empty(), onPress, DEFAULT_NARRATION) {

    init {
        setTooltip(Tooltip.create(tooltip))
        message = tooltip // narration reads it; the text itself isn't drawn (see renderWidget)
    }

    //? if >=1.21.11 {
    /*override fun renderContents(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        renderDefaultSprite(context) // the vanilla button frame, without its label
        icon()?.let { BadgeRenderer.draw(context, it, x + 2, y + 2, SIZE - 4) }
    }
    *///?} else {
    override fun renderWidget(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        val label = message
        message = Component.empty()
        super.renderWidget(context, mouseX, mouseY, delta) // the vanilla button frame, without its label
        message = label
        icon()?.let { BadgeRenderer.draw(context, it, x + 2, y + 2, SIZE - 4) }
    }
    //?}

    companion object {
        const val SIZE = 20
    }
}
