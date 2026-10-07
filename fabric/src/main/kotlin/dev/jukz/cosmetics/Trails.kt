package dev.jukz.cosmetics

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import dev.jukz.cosmetics.CosmeticCatalog.Model
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import dev.jukz.cosmetics.CosmeticCatalog.Style
//? if >=26.2 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper as KeyBindingHelper
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
//?}
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

object Trails {
    //? if >=1.21.11 {
    /*private val emoteKey = KeyMapping("key.jukz.emote", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC)
    *///?} else {
    private val emoteKey = KeyMapping("key.jukz.emote", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.misc")
    //?}

    private class Bit(val x: Double, val y: Double, val z: Double, val born: Long, val sprite: Int, val seed: Float, val idle: Boolean)

    private val bits = HashMap<UUID, ArrayDeque<Bit>>()

    fun init() {
        //? if >=26.2 {
        /*KeyBindingHelper.registerKeyMapping(emoteKey)
        *///?} else {
        KeyBindingHelper.registerKeyBinding(emoteKey)
        //?}
        TrailRenderer.register()
    }

    private const val IDLE_LIFE = 50

    private fun life(style: Style, idle: Boolean) = if (idle) IDLE_LIFE else when (style) {
        Style.FALL -> 70
        Style.TWINKLE -> 22
        Style.BOUNCE -> 36
    }

    fun tick(client: Minecraft) {
        while (emoteKey.consumeClick()) Cosmetics.emote()
        val level = client.level ?: return bits.clear()
        if (client.isPaused) return
        val now = level.gameTime
        val seen = HashSet<UUID>()
        for (player in level.players()) {
            val particle = Cosmetics.catalog.item(Cosmetics.loadoutFor(player.uuid)[Slot.TRAIL])?.particle ?: continue
            seen += player.uuid
            val list = bits.getOrPut(player.uuid) { ArrayDeque() }
            list.removeAll { now - it.born > life(particle.style, it.idle) }
            if (player.isInvisible || player.isSpectator || list.size > 48) continue
            val dx = player.x - player.xo
            val dz = player.z - player.zo
            val idle = dx * dx + dz * dz < 0.0004
            val every = if (idle) when (particle.style) { Style.FALL -> 8; Style.TWINKLE -> 4; Style.BOUNCE -> 7 }
                else when (particle.style) { Style.FALL -> 3; Style.TWINKLE -> 1; Style.BOUNCE -> 4 }
            if (now % every != 0L) continue
            val r = player.random
            val sprite = r.nextInt(particle.sprites.size)
            if (idle) {
                val y = when (particle.style) { Style.FALL -> 1.0 + r.nextDouble() * 0.5; Style.TWINKLE -> 0.2 + r.nextDouble() * 1.1; Style.BOUNCE -> 0.7 }
                list.addLast(Bit(player.x, player.y + y, player.z, now, sprite, r.nextFloat(), true))
                continue
            }
            val (y, spread) = when (particle.style) {
                Style.FALL -> 1.2 + r.nextDouble() * 0.6 to 0.6
                Style.TWINKLE -> 0.1 + r.nextDouble() * 1.2 to 0.5
                Style.BOUNCE -> 0.0 to 0.3
            }
            list.addLast(Bit(
                player.x - dx * 2 + (r.nextDouble() - 0.5) * spread, player.y + y, player.z - dz * 2 + (r.nextDouble() - 0.5) * spread,
                now, sprite, r.nextFloat(), false,
            ))
        }
        bits.keys.retainAll(seen)
    }

    /** Every trail in the world; [matrices] is camera-relative, the camera at ([cx], [cy], [cz]). */
    fun draw(matrices: PoseStack, cx: Double, cy: Double, cz: Double, partial: Float, emit: (Model, Float) -> Unit) {
        val level = Minecraft.getInstance().level ?: return
        val ticks = level.gameTime + partial
        val camera = cameraRotation()
        for ((player, list) in bits) {
            val particle = Cosmetics.catalog.item(Cosmetics.loadoutFor(player)[Slot.TRAIL])?.particle ?: continue
            for (b in list) {
                val life = life(particle.style, b.idle).toFloat()
                val a = ticks - b.born
                if (a < 0 || a > life) continue
                val t = a / life
                var opacity = if (t > 0.7f) (1 - t) / 0.3f else 1f
                var ox = 0.0; var oy = 0.0; var oz = 0.0
                var size = particle.size
                val rotation = Quaternionf()
                if (b.idle) {
                    val angle = b.seed * 2 * PI + a * 0.05
                    val radius = if (particle.style == Style.BOUNCE) 0.5 else 0.55 + 0.1 * sin(a * 0.1 + b.seed * 6)
                    ox = cos(angle) * radius
                    oz = sin(angle) * radius
                    oy = when (particle.style) {
                        Style.FALL -> -a * 0.008
                        Style.TWINKLE -> a * 0.008
                        Style.BOUNCE -> sin(a * 0.15 + b.seed * 6) * 0.06
                    }
                    opacity = sin(PI * t).toFloat().coerceAtMost(1f)
                    if (particle.style == Style.BOUNCE) rotation.rotateY(-angle.toFloat()).rotateX(a * 0.08f)
                    else rotation.set(camera).rotateZ(((a * 3 + b.seed * 360) * PI / 180).toFloat())
                    if (particle.style == Style.TWINKLE) size *= 0.6f + 0.4f * sin(a * 0.4f + b.seed * 6)
                } else when (particle.style) {
                    Style.FALL -> {
                        ox = sin(a * 0.12 + b.seed * 6) * 0.18
                        oz = cos(a * 0.1 + b.seed * 6) * 0.18
                        oy = -a * 0.012
                        rotation.set(camera).rotateZ(((a * 5 + b.seed * 360) * PI / 180).toFloat())
                    }
                    Style.TWINKLE -> {
                        oy = a * 0.015
                        size *= sin(PI * t).toFloat()
                        rotation.set(camera).rotateZ((b.seed * PI / 2).toFloat())
                    }
                    Style.BOUNCE -> {
                        val angle = b.seed * 2 * PI
                        val far = (1 - exp(-a / 10.0)) * 0.4
                        ox = cos(angle) * far
                        oz = sin(angle) * far
                        val hop = floor(a / 9f)
                        oy = 0.32 * 0.55.pow(hop.toDouble()) * sin(PI * (a - hop * 9) / 9) + size * 2
                        rotation.rotateY(a * 0.14f + b.seed * 6)
                    }
                }
                matrices.pushPose()
                place(matrices, (b.x - cx + ox).toFloat(), (b.y - cy + oy).toFloat(), (b.z - cz + oz).toFloat(), rotation, size, particle.style != Style.BOUNCE)
                emit(particle.sprites[b.sprite], opacity)
                matrices.popPose()
            }
        }
    }

    fun drawEmote(matrices: PoseStack, emote: Cosmetics.Emote, emit: (Model, Float) -> Unit) {
        val frames = emote.item.frames
        val plaque = (if (frames.isEmpty()) emote.item.plaque else frames[(emote.age / emote.item.frameMs).toInt().coerceAtMost(frames.size - 1)]) ?: return
        val size = emote.item.art?.size ?: 16
        val t = (emote.age / 250f).coerceAtMost(1f)
        val pop = 1 + 2.7f * (t - 1).pow(3) + 1.7f * (t - 1).pow(2)
        val opacity = (emote.left / 400f).coerceIn(0f, 1f)
        matrices.pushPose()
        matrices.translate(0f, -1.6f - sin(emote.age / 300.0).toFloat() * 0.04f, 0f)
        val at = matrices.last().pose().getTranslation(Vector3f())
        place(matrices, at.x, at.y, at.z, cameraRotation(), 0.48f / size * pop, true)
        emit(plaque, opacity)
        matrices.popPose()
    }

    /** Camera-space pose at (x, y, z); flat sprites flip y so their rows run down. */
    private fun place(matrices: PoseStack, x: Float, y: Float, z: Float, rotation: Quaternionf, scale: Float, flat: Boolean) {
        val entry = matrices.last()
        entry.pose().set(Matrix4f().translation(x, y, z).rotate(rotation).scale(scale, if (flat) -scale else scale, scale))
        entry.normal().set(Matrix3f().rotation(rotation))
    }

    fun cameraRotation(): Quaternionf {
        //? if >=26.2 {
        /*return Quaternionf(Minecraft.getInstance().gameRenderer.mainCamera().rotation())
        *///?} else {
        return Quaternionf(Minecraft.getInstance().gameRenderer.mainCamera.rotation())
        //?}
    }
}
