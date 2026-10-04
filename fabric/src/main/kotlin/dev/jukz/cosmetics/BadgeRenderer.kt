package dev.jukz.cosmetics

import net.minecraft.client.gui.DrawContext

/**
 * Draws a badge's ASCII art as a [px]-sized square at ([x], [y]), in GUI pixels; [opacity] < 1 fades it
 * (locked items).
 */
object BadgeRenderer {
    fun draw(context: DrawContext, badge: CosmeticCatalog.Badge, x: Int, y: Int, px: Int, opacity: Float = 1f) {
        val matrices = context.matrices
        matrices.push()
        matrices.translate(x.toFloat(), y.toFloat(), 0f)
        // A 16x16 badge drawn 8 px tall in the tab list is one art pixel per screen pixel at GUI scale 2.
        val scale = px.toFloat() / badge.size
        matrices.scale(scale, scale, 1f)
        for (run in badge.runs) context.fill(run.x, run.y, run.x + run.length, run.y + 1, fade(run.argb, opacity))
        matrices.pop()
    }

    private fun fade(argb: Int, opacity: Float): Int {
        if (opacity >= 1f) return argb
        val alpha = ((argb ushr 24) * opacity).toInt()
        return (alpha shl 24) or (argb and 0xFFFFFF)
    }
}
