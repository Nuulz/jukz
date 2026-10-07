package dev.jukz.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.ModelPart
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.min

/**
 * Emotes that move the player: the arm pose comes from the PlayerModel mixin, the prop (the coin) is
 * drawn by the cosmetics layer at the hand. Times are ms into the emote.
 */
object Gestures {
    private const val LAUNCH = 650f
    private const val CATCH = 2150f
    private const val HEIGHT = 22f // px above the hand at the top of the toss

    // ms → arm angles (x, z): raise the hand, flick the thumb, wait, catch with a little dip, lower it
    private val KEYS = arrayOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(350f, -1.25f, 0.15f), floatArrayOf(560f, -1.2f, 0.15f),
        floatArrayOf(660f, -1.6f, 0.1f), floatArrayOf(900f, -1.3f, 0.12f), floatArrayOf(2100f, -1.3f, 0.12f),
        floatArrayOf(2250f, -1.1f, 0.12f), floatArrayOf(2500f, -1.3f, 0.15f), floatArrayOf(2700f, -1.3f, 0.15f),
        floatArrayOf(3000f, 0f, 0f),
    )

    fun active(player: UUID): Cosmetics.Emote? = Cosmetics.emoting(player)?.takeIf { it.item.gesture == "coin_flip" }

    /** Called after the model's own animation: blends the right arm into the toss. */
    fun poseArm(player: UUID, arm: ModelPart) {
        val emote = active(player) ?: return
        val t = emote.age.toFloat()
        val i = KEYS.indexOfLast { it[0] <= t }.coerceIn(0, KEYS.size - 2)
        val a = KEYS[i]; val b = KEYS[i + 1]
        val u = smooth(((t - a[0]) / (b[0] - a[0])).coerceIn(0f, 1f))
        val w = min(1f, min(t / 300f, (3000f - t) / 300f)).coerceAtLeast(0f)
        arm.xRot += (a[1] + (b[1] - a[1]) * u - arm.xRot) * w
        arm.yRot += (-0.2f - arm.yRot) * w
        arm.zRot += (a[2] + (b[2] - a[2]) * u - arm.zRot) * w
    }

    /** The coin: on the thumb, then up spinning and back into the hand. [matrices] is in model space. */
    fun drawProp(matrices: PoseStack, player: UUID, arm: ModelPart, emit: (CosmeticCatalog.Model) -> Unit) {
        val emote = active(player) ?: return
        val coin = emote.item.prop ?: return
        val t = emote.age.toFloat()
        if (t < 300f || t > 2850f) return
        val hand = Vector3f(-1f, 10.5f, -1.5f).rotate(Quaternionf().rotationZYX(arm.zRot, arm.yRot, arm.xRot)).add(arm.x, arm.y, arm.z)
        var lift = 0f
        var spin = (PI / 2).toFloat() // lying flat on the thumb
        if (t in LAUNCH..CATCH) {
            val u = (t - LAUNCH) / (CATCH - LAUNCH)
            lift = 4 * HEIGHT * u * (1 - u)
            spin += u * 9 * PI.toFloat()
        }
        matrices.pushPose()
        matrices.translate(hand.x / 16f, (hand.y - 1f - lift) / 16f, hand.z / 16f)
        matrices.mulPose(Quaternionf().rotationX(spin))
        val s = 4f / 16f / (emote.item.propSize)
        matrices.scale(s, s, s)
        emit(coin)
        matrices.popPose()
    }

    private fun smooth(x: Float) = x * x * (3 - 2 * x)
}
