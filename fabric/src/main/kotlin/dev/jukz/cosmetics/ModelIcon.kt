package dev.jukz.cosmetics

import net.minecraft.client.gui.GuiGraphics
import com.mojang.blaze3d.platform.Lighting
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import com.mojang.math.Axis
import java.util.IdentityHashMap

/** Draws a 3D cosmetic as a GUI icon: centred in a [px] square, turned to show its front, side and top. */
object ModelIcon {
    private val WHITE: ResourceLocation = ResourceLocation.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")

    private class Bounds(val cx: Float, val cy: Float, val cz: Float, val size: Float)

    private val bounds = IdentityHashMap<CosmeticCatalog.Model, Bounds>()

    fun draw(context: GuiGraphics, model: CosmeticCatalog.Model, x: Int, y: Int, px: Int, opacity: Float = 1f, fromBehind: Boolean = false) {
        val b = bounds.getOrPut(model) { boundsOf(model) }
        val matrices = context.pose()
        matrices.pushPose()
        matrices.translate(x + px / 2f, y + px / 2f, 150f)
        val scale = px * 0.62f / b.size
        matrices.scale(scale, scale, scale)
        // GUI space has y down like bone space; turn the front (-z) towards the viewer, then tilt to show the top.
        matrices.mulPose(Axis.XP.rotationDegrees(-28f))
        matrices.mulPose(Axis.YP.rotationDegrees(35f + if (fromBehind) 0f else 180f)) // back pieces show their outside
        matrices.translate(-b.cx, -b.cy, -b.cz)
        Lighting.setupForEntityInInventory() // entity-style lighting, as for mobs drawn in GUIs
        val buffer = context.bufferSource().getBuffer(RenderType.entityTranslucent(WHITE))
        VoxelMesh.emit(matrices.last(), buffer, model, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, opacity)
        context.flush()
        Lighting.setupFor3DItems()
        matrices.popPose()
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
