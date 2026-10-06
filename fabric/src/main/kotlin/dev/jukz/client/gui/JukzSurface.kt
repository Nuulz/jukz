package dev.jukz.client.gui

import dev.jukz.compat.OwoUIGraphics
import dev.jukz.compat.ParentUIComponent
import dev.jukz.compat.UIComponent
import dev.jukz.compat.UIComponents
import dev.jukz.compat.jukzText
import dev.jukz.cosmetics.BadgeRenderer
import dev.jukz.cosmetics.CosmeticCatalog
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.core.Surface
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The jukz pixel look, in one place: every panel, button and card in the mod is drawn by these, so a
 * restyle is an edit here. Everything is plain `fill` rectangles on the pixel grid — no textures, no
 * shaders — the one drawing call that hasn't changed across 1.21.1, 1.21.11 and 26.2.
 *
 * The recipe: boxes with 1 px chamfered corners (pixel "rounding"), a lighter rim, a 1 px top highlight,
 * and for the selected / worn state a soft glow of two translucent rings. Deep navy on jukz blue.
 */
object JukzSurface {

    enum class Tone { IDLE, HOVER, SELECTED, ON, LOCKED, DISABLED }

    /** Where a selected [iconButton] points: an arrow to its right (side menu) or a notch under it (tabs). */
    enum class Pointer { NONE, RIGHT, DOWN }

    const val BLUE = 0xFF1E66F5.toInt()
    const val GREEN = 0xFF4BD66E.toInt()
    const val TEXT = 0xFFE6EEFF.toInt()
    const val TEXT_DIM = 0xFF7F93BF.toInt()

    private const val WINDOW = 0xF00A1430.toInt()
    private const val WINDOW_RIM = 0xFF2B56B0.toInt()
    private const val WELL = 0xFF0A1734.toInt()
    private const val WELL_RIM = 0xFF1C3570.toInt()
    private const val POINTER = 0xFF8FB8FF.toInt()
    private const val HILITE = 0x22FFFFFF

    /** The window: rim, glow and a soft shadow under it. */
    fun window(): Surface = Surface { c, p ->
        val x = p.x(); val y = p.y(); val w = p.width(); val h = p.height()
        c.fill(x + 2, y + h, x + w - 2, y + h + 3, 0x55000000)
        glow(c, x, y, w, h, WINDOW_RIM)
        framed(c, x, y, w, h, WINDOW_RIM, WINDOW)
    }

    /** A sunken area inside the window (side menu, content). */
    fun well(): Surface = Surface { c, p -> framed(c, p.x(), p.y(), p.width(), p.height(), WELL_RIM, WELL, hilite = false) }

    /** The side menu's well, with a few sparkles in the empty room between the sections and the bottom buttons. */
    fun sideWell(): Surface = Surface { c, p ->
        val x = p.x(); val y = p.y(); val w = p.width(); val h = p.height()
        framed(c, x, y, w, h, WELL_RIM, WELL, hilite = false)
        listOf(0.30 to 0.45, 0.70 to 0.52, 0.42 to 0.60).forEach { (fx, fy) ->
            star(c, x + (w * fx).toInt(), y + (h * fy).toInt(), 0x663E6FD8)
        }
    }

    /** A horizontal rule with a sparkle in its middle (a [p]-wide, 5 px tall component). */
    fun divider(): Surface = Surface { c, p ->
        val x = p.x(); val y = p.y() + 2; val w = p.width()
        c.fill(x + 4, y, x + w / 2 - 5, y + 1, 0xFF1C3570.toInt())
        c.fill(x + w / 2 + 5, y, x + w - 4, y + 1, 0xFF1C3570.toInt())
        star(c, x + w / 2, y, 0xFF5B8FFF.toInt())
    }

    /** The preview stage: a well with corner brackets and a few sparkles around you. */
    fun stage(): Surface = Surface { c, p ->
        val x = p.x(); val y = p.y(); val w = p.width(); val h = p.height()
        framed(c, x, y, w, h, WELL_RIM, WELL, hilite = false)
        val k = 0xFF2B4C94.toInt()
        listOf(x + 4 to y + 4, x + w - 9 to y + 4, x + 4 to y + h - 9, x + w - 9 to y + h - 9).forEachIndexed { i, (cx, cy) ->
            val right = i % 2 == 1
            val bottom = i >= 2
            c.fill(cx, if (bottom) cy + 4 else cy, cx + 5, if (bottom) cy + 5 else cy + 1, k)
            c.fill(if (right) cx + 4 else cx, cy, if (right) cx + 5 else cx + 1, cy + 5, k)
        }
        listOf(0.16 to 0.20, 0.86 to 0.28, 0.12 to 0.60, 0.88 to 0.66).forEach { (fx, fy) ->
            star(c, x + (w * fx).toInt(), y + (h * fy).toInt(), 0x993E6FD8.toInt())
        }
    }

    /** A card in a grid or list. */
    fun card(tone: Tone): Surface = Surface { c, p ->
        val x = p.x(); val y = p.y(); val w = p.width(); val h = p.height()
        when (tone) {
            Tone.ON -> { glow(c, x, y, w, h, GREEN); framed(c, x, y, w, h, GREEN, 0xFF0F3324.toInt()) }
            Tone.HOVER -> { glow(c, x, y, w, h, BLUE); framed(c, x, y, w, h, 0xFF5B8FFF.toInt(), 0xFF16306A.toInt()) }
            Tone.SELECTED -> framed(c, x, y, w, h, POINTER, BLUE)
            Tone.LOCKED, Tone.DISABLED -> framed(c, x, y, w, h, 0xFF16264A.toInt(), 0xFF0A1430.toInt(), hilite = false)
            Tone.IDLE -> framed(c, x, y, w, h, 0xFF22427F.toInt(), 0xFF0F2048.toInt())
        }
    }

    /** Plain buttons: a raised navy block, brighter on hover, blue when selected, dim when off. */
    fun button(selected: () -> Boolean = { false }): ButtonComponent.Renderer = ButtonComponent.Renderer { c, b, _ ->
        buttonBox(c, b.x, b.y, b.width, b.height, selected(), b.isHovered, b.active)
    }

    /** A primary action (bring, upload, try again): green block. */
    fun primary(): ButtonComponent.Renderer = ButtonComponent.Renderer { c, b, _ ->
        greenBox(c, b.x, b.y, b.width, b.height, b.isHovered, b.active)
    }

    private fun greenBox(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Boolean, active: Boolean) {
        val body = if (!active) 0xFF1E3A2A.toInt() else if (hovered) 0xFF3E9A55.toInt() else 0xFF2E7D43.toInt()
        framed(c, x, y, w, h, if (active) GREEN else 0xFF2A4A36.toInt(), body)
    }

    /**
     * A button with a pixel icon and its own label (owo's centred label is left empty): left-aligned or
     * [centred], with the [pointer] marker while selected and an optional [trailing] icon (a "›").
     */
    fun iconButton(
        text: String,
        icon: CosmeticCatalog.Art?,
        selected: () -> Boolean = { false },
        centred: Boolean = false,
        pointer: Pointer = Pointer.NONE,
        trailing: CosmeticCatalog.Art? = null,
        primary: Boolean = false,
        onPress: () -> Unit,
    ): ButtonComponent {
        val button = UIComponents.button(Component.empty()) { onPress() }
        button.renderer(ButtonComponent.Renderer { c, b, _ ->
            val on = selected()
            val x = b.x; val y = b.y; val w = b.width; val h = b.height
            if (primary) greenBox(c, x, y, w, h, b.isHovered, b.active) else buttonBox(c, x, y, w, h, on, b.isHovered, b.active)
            if (on && (pointer == Pointer.RIGHT || (centred && pointer == Pointer.NONE))) c.fill(x + 1, y + 3, x + 3, y + h - 3, TEXT) // selection bar
            // Markers stay inside the button: owo clips a widget's drawing to its bounds.
            if (on && pointer == Pointer.RIGHT) for (i in 0..2) c.fill(x + w - 6 + i, y + h / 2 - 2 + i, x + w - 5 + i, y + h / 2 + 3 - i, TEXT)
            if (on && pointer == Pointer.DOWN) for (i in 0..1) c.fill(x + w / 2 - 2 + i, y + h - 3 + i, x + w / 2 + 3 - i, y + h - 2 + i, TEXT)
            val font = Minecraft.getInstance().font
            val iconW = if (icon == null) 0 else if (text.isEmpty()) 9 else 13
            val start = if (centred) x + (w - iconW - font.width(text)) / 2 else x + 6
            val mid = y + (h - 9) / 2
            icon?.let { BadgeRenderer.draw(c, it, start, mid, 9) }
            c.jukzText(font, text, start + iconW, mid + 1, if (b.active || on) TEXT else TEXT_DIM)
            trailing?.let { BadgeRenderer.draw(c, it, x + w - 13, mid, 9) }
        })
        return button
    }

    /** Give every plain button under [root] the jukz look (buttons keep any renderer set on purpose). */
    fun restyle(root: UIComponent) {
        if (root is ButtonComponent && root.renderer() === ButtonComponent.Renderer.VANILLA) root.renderer(button())
        if (root is ParentUIComponent) root.children().forEach(::restyle)
    }

    /** The small 5 px sparkle used as decoration. */
    fun star(c: OwoUIGraphics, cx: Int, cy: Int, color: Int) {
        c.fill(cx, cy - 2, cx + 1, cy + 3, color)
        c.fill(cx - 2, cy, cx + 3, cy + 1, color)
    }

    private fun buttonBox(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, selected: Boolean, hovered: Boolean, active: Boolean) {
        when {
            selected -> { glow(c, x, y, w, h, BLUE); framed(c, x, y, w, h, POINTER, BLUE) }
            !active -> framed(c, x, y, w, h, 0xFF16264A.toInt(), 0xFF0C1836.toInt(), hilite = false)
            hovered -> framed(c, x, y, w, h, 0xFF4A78D8.toInt(), 0xFF1C3A78.toInt())
            else -> framed(c, x, y, w, h, 0xFF2A4C94.toInt(), 0xFF142A5A.toInt())
        }
    }

    /** A box with chamfered corners: [rim] 1 px around [body], plus a faint top highlight. */
    private fun framed(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, rim: Int, body: Int, hilite: Boolean = true) {
        chamfer(c, x, y, w, h, rim)
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, body)
        if (hilite) c.fill(x + 2, y + 1, x + w - 2, y + 2, HILITE)
    }

    /** Two translucent rings outside the box, tinted with [color]. */
    private fun glow(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        val rgb = color and 0xFFFFFF
        ring(c, x - 2, y - 2, w + 4, h + 4, 0x22000000 or rgb)
        ring(c, x - 1, y - 1, w + 2, h + 2, 0x55000000 or rgb)
    }

    private fun ring(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        c.fill(x + 1, y, x + w - 1, y + 1, color)
        c.fill(x + 1, y + h - 1, x + w - 1, y + h, color)
        c.fill(x, y + 1, x + 1, y + h - 1, color)
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }

    /** A filled rectangle without its four corner pixels. */
    private fun chamfer(c: OwoUIGraphics, x: Int, y: Int, w: Int, h: Int, color: Int) {
        c.fill(x + 1, y, x + w - 1, y + h, color)
        c.fill(x, y + 1, x + 1, y + h - 1, color)
        c.fill(x + w - 1, y + 1, x + w, y + h - 1, color)
    }
}
