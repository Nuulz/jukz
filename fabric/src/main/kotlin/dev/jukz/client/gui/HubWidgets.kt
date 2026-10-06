package dev.jukz.client.gui

import dev.jukz.compat.BaseUIComponent
import dev.jukz.compat.OwoUIGraphics
import dev.jukz.compat.UIComponent
import dev.jukz.compat.jukzText
import dev.jukz.cosmetics.BadgeRenderer
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.Minecraft

/** Small drawn pieces of the hub that owo has no component for. */
object HubWidgets {

    /** [text] drawn [scale] times the normal size (the headline: your name). */
    class BigText(private val text: String, private val scale: Int, private val color: Int) : BaseUIComponent() {
        init {
            val font = Minecraft.getInstance().font
            sizing(Sizing.fixed(font.width(text) * scale), Sizing.fixed(9 * scale))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val matrices = context.pose()
            //? if >=1.21.11 {
            /*matrices.pushMatrix()
            matrices.translate(x.toFloat(), y.toFloat())
            matrices.scale(scale.toFloat(), scale.toFloat())
            *///?} else {
            matrices.pushPose()
            matrices.translate(x.toFloat(), y.toFloat(), 0f)
            matrices.scale(scale.toFloat(), scale.toFloat(), 1f)
            //?}
            context.jukzText(Minecraft.getInstance().font, text, 0, 0, color)
            //? if >=1.21.11 {
            /*matrices.popMatrix()
            *///?} else {
            matrices.popPose()
            //?}
        }
    }

    /** A progress track [fraction] full, 4 px tall, as wide as its parent lets it. */
    class Bar(private val fraction: Double) : BaseUIComponent() {
        init {
            sizing(Sizing.expand(100), Sizing.fixed(4))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            context.fill(x, y, x + width, y + height, 0xFF16264A.toInt())
            val filled = (width * fraction.coerceIn(0.0, 1.0)).toInt().coerceAtLeast(if (fraction > 0) 2 else 0)
            context.fill(x, y, x + filled, y + height, JukzSurface.BLUE)
            context.fill(x, y, x + filled, y + 1, 0x55FFFFFF)
        }
    }

    /** A "›" that nudges right while the pointer is over [owner] (the row or tile it sits in). */
    class Chevron(private val owner: UIComponent) : BaseUIComponent() {
        init {
            sizing(Sizing.fixed(11), Sizing.fixed(9))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            val nudge = if (owner.isInBoundingBox(mouseX.toDouble(), mouseY.toDouble())) 2 else 0
            BadgeRenderer.draw(context, HubIcons.ARROW, x + nudge, y, 9)
        }
    }

    /** A small filled dot (status lights). */
    class Dot(private val color: Int, private val size: Int = 5) : BaseUIComponent() {
        init {
            sizing(Sizing.fixed(size), Sizing.fixed(size))
        }

        override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
            context.fill(x + 1, y, x + size - 1, y + size, color)
            context.fill(x, y + 1, x + size, y + size - 1, color)
        }
    }
}
