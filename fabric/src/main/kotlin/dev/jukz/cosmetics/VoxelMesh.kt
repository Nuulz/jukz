package dev.jukz.cosmetics

import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import org.joml.Matrix4f
import org.joml.Vector3f

/** Emits a voxel model's faces (in bone pixels) into an entity-format vertex consumer. */
object VoxelMesh {
    /** What the wearer is doing, for rigged pieces: [walk] 0 = standing still … 1 = sprinting. */
    class Pose(val ticks: Float, val walk: Float = 0f, val sneaking: Boolean = false)

    private const val MOVING = 0.15f

    fun emit(
        entry: PoseStack.Pose, buffer: VertexConsumer, model: CosmeticCatalog.Model, light: Int, overlay: Int,
        opacity: Float = 1f, pose: Pose? = null,
    ) {
        val rig = model.rig
        if (rig == null || pose == null) {
            emitQuads(entry, buffer, model.quads, null, light, overlay, opacity)
            return
        }
        emitQuads(entry, buffer, rig.body, null, light, overlay, opacity)
        val walk = pose.walk.coerceIn(0f, 1f)
        val transforms = arrayOfNulls<Matrix4f>(rig.parts.size)
        rig.parts.forEachIndexed { i, part ->
            val m = if (part.parent >= 0) Matrix4f(transforms[part.parent]) else Matrix4f()
            partTransform(part, pose, walk, m)
            transforms[i] = m
            if (visible(part.shown, walk, pose.sneaking)) {
                emitQuads(entry, buffer, part.frameAt(pose.ticks), m, light, overlay, opacity)
            }
        }
    }

    private fun visible(shown: CosmeticCatalog.Shown, walk: Float, sneaking: Boolean) = when (shown) {
        CosmeticCatalog.Shown.ALWAYS -> true
        CosmeticCatalog.Shown.STILL -> walk < MOVING
        CosmeticCatalog.Shown.MOVING -> walk >= MOVING
        CosmeticCatalog.Shown.SNEAKING -> sneaking
        CosmeticCatalog.Shown.STANDING -> !sneaking
        CosmeticCatalog.Shown.NEVER -> false
    }

    /** Idle blends into run with [walk]; sneaking replaces both. Each state keeps its own wave, so blending never jumps. */
    private fun partTransform(part: CosmeticCatalog.Part, pose: Pose, walk: Float, m: Matrix4f) {
        val idle = part.idle ?: return
        val rot = FloatArray(3)
        val move = FloatArray(3)
        fun add(motion: CosmeticCatalog.Motion, weight: Float) {
            if (weight <= 0f) return
            val wave = kotlin.math.sin(pose.ticks * motion.speed + motion.phase)
            for (a in 0 until 3) {
                rot[a] += (motion.base[a] + motion.amp[a] * wave) * weight
                move[a] += motion.move[a] * wave * weight
            }
        }
        if (pose.sneaking && part.sneak != null) {
            add(part.sneak, 1f)
        } else {
            val run = part.run
            if (run == null) add(idle, 1f) else { add(idle, 1f - walk); add(run, walk) }
        }
        val (px, py, pz) = part.pivot
        m.translate(px + move[0], py + move[1], pz + move[2])
        val (rx, ry, rz) = rot.map { Math.toRadians(it.toDouble()).toFloat() }
        if (part.zyx) m.rotateZYX(rz, ry, rx) else m.rotateXYZ(rx, ry, rz)
        m.translate(-px, -py, -pz)
    }

    private fun emitQuads(
        entry: PoseStack.Pose, buffer: VertexConsumer, quads: List<CosmeticCatalog.Quad>, m: Matrix4f?,
        light: Int, overlay: Int, opacity: Float,
    ) {
        val v = Vector3f()
        val n = Vector3f()
        for (quad in quads) {
            val argb = if (opacity >= 1f) quad.argb else (((quad.argb ushr 24) * opacity).toInt() shl 24) or (quad.argb and 0xFFFFFF)
            val c = quad.corners
            n.set(quad.nx, quad.ny, quad.nz)
            m?.transformDirection(n)
            for (i in 0 until 4) {
                v.set(c[i * 3], c[i * 3 + 1], c[i * 3 + 2])
                m?.transformPosition(v)
                buffer.addVertex(entry, v.x, v.y, v.z)
                    .setColor(argb)
                    .setUv(0.5f, 0.5f)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(entry, n.x, n.y, n.z)
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
