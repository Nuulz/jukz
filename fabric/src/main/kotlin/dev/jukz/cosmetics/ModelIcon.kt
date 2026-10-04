package dev.jukz.cosmetics

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis
import java.util.IdentityHashMap

/** Draws a 3D cosmetic as a GUI icon: centred in a [px] square, turned to show its front, side and top. */
object ModelIcon {
    private val WHITE: Identifier = Identifier.of("jukz", "textures/cosmetics/white.png")

    private class Bounds(val cx: Float, val cy: Float, val cz: Float, val size: Float)

    private val bounds = IdentityHashMap<CosmeticCatalog.Model, Bounds>()

    fun draw(context: DrawContext, model: CosmeticCatalog.Model, x: Int, y: Int, px: Int, opacity: Float = 1f, fromBehind: Boolean = false) {
        val b = bounds.getOrPut(model) { boundsOf(model) }
        val matrices = context.matrices
        matrices.push()
        matrices.translate(x + px / 2f, y + px / 2f, 150f)
        val scale = px * 0.62f / b.size
        matrices.scale(scale, scale, scale)
        // GUI space has y down like bone space; turn the front (-z) towards the viewer, then tilt to show the top.
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-28f))
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(35f + if (fromBehind) 0f else 180f)) // back pieces show their outside
        matrices.translate(-b.cx, -b.cy, -b.cz)
        DiffuseLighting.method_34742() // entity-style lighting, as for mobs drawn in GUIs
        val buffer = context.vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE))
        VoxelMesh.emit(matrices.peek(), buffer, model, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV, opacity)
        context.draw()
        DiffuseLighting.enableGuiDepthLighting()
        matrices.pop()
    }

    private fun boundsOf(model: CosmeticCatalog.Model): Bounds {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (q in model.quads) for (i in 0 until 4) {
            val (x, y, z) = Triple(q.corners[i * 3], q.corners[i * 3 + 1], q.corners[i * 3 + 2])
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
            minZ = minOf(minZ, z); maxZ = maxOf(maxZ, z)
        }
        val size = maxOf(maxX - minX, maxY - minY, maxZ - minZ).coerceAtLeast(1f)
        return Bounds((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2, size)
    }
}
