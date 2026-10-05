package dev.jukz.cosmetics

import net.minecraft.client.gui.GuiGraphics

/**
 * Draws a badge's ASCII art as a [px]-sized square at ([x], [y]), in GUI pixels; [opacity] < 1 fades it
 * (locked items).
 */
object BadgeRenderer {
    fun draw(context: GuiGraphics, badge: CosmeticCatalog.Item, x: Int, y: Int, px: Int, opacity: Float = 1f) {
        draw(context, badge.art ?: return, x, y, px, opacity)
    }

    fun draw(context: GuiGraphics, art: CosmeticCatalog.Art, x: Int, y: Int, px: Int, opacity: Float = 1f) {
        val matrices = context.pose()
        // A 16x16 badge drawn 8 px tall in the tab list is one art pixel per screen pixel at GUI scale 2.
        val scale = px.toFloat() / art.size
        //? if >=1.21.11 {
        /*matrices.pushMatrix()
        matrices.translate(x.toFloat(), y.toFloat())
        matrices.scale(scale, scale)
        *///?} else {
        matrices.pushPose()
        matrices.translate(x.toFloat(), y.toFloat(), 0f)
        matrices.scale(scale, scale, 1f)
        //?}
        for (run in art.runs) context.fill(run.x, run.y, run.x + run.length, run.y + 1, fade(run.argb, opacity))
        //? if >=1.21.11 {
        /*matrices.popMatrix()
        *///?} else {
        matrices.popPose()
        //?}
    }

    private fun fade(argb: Int, opacity: Float): Int {
        if (opacity >= 1f) return argb
        val alpha = ((argb ushr 24) * opacity).toInt()
        return (alpha shl 24) or (argb and 0xFFFFFF)
    }
}
