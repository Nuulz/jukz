package dev.jukz.cosmetics

//? if >=1.21.11 {
/*import com.mojang.math.Axis
import net.minecraft.client.gui.GuiGraphics
import org.joml.Matrix3f
import org.joml.Vector3f
import java.util.IdentityHashMap

/**
 * Draws a 3D cosmetic as a GUI icon: centred in a [px] square, turned to show its front, side and top.
 * (1.21.6+: the voxels are flat-coloured, so they are projected to 2D faces and drawn back to front with
 * block-style shading, instead of rendering each icon to its own texture.)
 */
object ModelIcon {
    private class Bounds(val cx: Float, val cy: Float, val cz: Float, val size: Float)

    private val bounds = IdentityHashMap<CosmeticCatalog.Model, Bounds>()

    fun draw(context: GuiGraphics, model: CosmeticCatalog.Model, x: Int, y: Int, px: Int, opacity: Float = 1f, fromBehind: Boolean = false) {
        val b = bounds.getOrPut(model) { boundsOf(model) }
        val scale = px * 0.62f / b.size
        // GUI space has y down like bone space; turn the front (-z) towards the viewer, then tilt to show the top.
        val turn = Matrix3f()
            .rotate(Axis.XP.rotationDegrees(-28f))
            .rotate(Axis.YP.rotationDegrees(35f + if (fromBehind) 0f else 180f)) // back pieces show their outside
        val faces = ArrayList<FlatQuads.Face>(model.quads.size)
        val p = Vector3f()
        val n = Vector3f()
        for (q in model.quads) {
            turn.transform(n.set(q.nx, q.ny, q.nz))
            if (n.z < 0f) continue // facing away from the viewer
            val xs = FloatArray(4); val ys = FloatArray(4); var depth = 0f
            for (i in 0 until 4) {
                turn.transform(p.set(q.corners[i * 3] - b.cx, q.corners[i * 3 + 1] - b.cy, q.corners[i * 3 + 2] - b.cz))
                xs[i] = x + px / 2f + p.x * scale
                ys[i] = y + px / 2f + p.y * scale
                depth += p.z
            }
            faces += FlatQuads.Face(xs, ys, depth, shade(q.argb, n, opacity))
        }
        faces.sortBy { it.depth } // far faces first
        FlatQuads.submit(context, faces, x, y, x + px, y + px)
    }

    /** Light from above and in front, like blocks in an inventory: tops brightest, sides darker. */
    private fun shade(argb: Int, normal: Vector3f, opacity: Float): Int {
        val light = (0.55f + 0.45f * maxOf(0f, -normal.y * 0.8f + normal.z * 0.6f)).coerceAtMost(1f)
        val r = ((argb shr 16 and 0xFF) * light).toInt()
        val g = ((argb shr 8 and 0xFF) * light).toInt()
        val bl = ((argb and 0xFF) * light).toInt()
        val a = ((argb ushr 24) * opacity).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or bl
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
*///?} else {
import net.minecraft.client.gui.GuiGraphics
import com.mojang.blaze3d.platform.Lighting
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.RenderType
import dev.jukz.compat.Identifier
import com.mojang.math.Axis
import java.util.IdentityHashMap

/** Draws a 3D cosmetic as a GUI icon: centred in a [px] square, turned to show its front, side and top. */
object ModelIcon {
    private val WHITE: Identifier = Identifier.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")

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
//?}
