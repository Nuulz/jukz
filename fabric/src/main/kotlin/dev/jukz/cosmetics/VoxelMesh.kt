package dev.jukz.cosmetics

import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis

/** Emits a voxel model's faces (in bone pixels) into an entity-format vertex consumer. */
object VoxelMesh {
    fun emit(entry: PoseStack.Pose, buffer: VertexConsumer, model: CosmeticCatalog.Model, light: Int, overlay: Int, opacity: Float = 1f) {
        for (quad in model.quads) {
            val argb = if (opacity >= 1f) quad.argb else (((quad.argb ushr 24) * opacity).toInt() shl 24) or (quad.argb and 0xFFFFFF)
            val c = quad.corners
            for (i in 0 until 4) {
                buffer.addVertex(entry, c[i * 3], c[i * 3 + 1], c[i * 3 + 2])
                    .setColor(argb)
                    .setUv(0.5f, 0.5f)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(entry, quad.nx, quad.ny, quad.nz)
            }
        }
    }

    /** The item's idle motion (halo bobbing, …); [ticks] is the entity's age plus the tick delta. */
    fun animate(model: CosmeticCatalog.Model, ticks: Float, matrices: PoseStack) {
        when (model.animation) {
            CosmeticCatalog.Animation.NONE -> {}
            CosmeticCatalog.Animation.BOB -> matrices.translate(0f, kotlin.math.sin(ticks * 0.08f) * 0.6f, 0f)
            CosmeticCatalog.Animation.SPIN -> {
                matrices.translate(model.centerX, 0f, model.centerZ)
                matrices.mulPose(Axis.YP.rotationDegrees(ticks * 2f))
                matrices.translate(-model.centerX, 0f, -model.centerZ)
            }
        }
    }

}
