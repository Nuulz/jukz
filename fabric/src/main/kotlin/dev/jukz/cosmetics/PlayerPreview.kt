package dev.jukz.cosmetics

import com.mojang.authlib.GameProfile
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.entity.model.EntityModelLayers
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.SkinTextures
import net.minecraft.util.Identifier
import net.minecraft.util.math.RotationAxis

/**
 * A player model in a GUI wearing a loadout — no entity or world needed, so it works on the title
 * screen too. Draws the vanilla player model with [profile]'s skin, then each 3D piece on its bone
 * exactly as [CosmeticsFeatureRenderer] does in the world.
 */
class PlayerPreview(private val profile: GameProfile) {
    private val client = MinecraftClient.getInstance()
    private val skin: SkinTextures = client.skinProvider.getSkinTextures(profile)
    private val model = PlayerEntityModel<AbstractClientPlayerEntity>(
        client.entityModelLoader.getModelPart(if (skin.model() == SkinTextures.Model.SLIM) EntityModelLayers.PLAYER_SLIM else EntityModelLayers.PLAYER),
        skin.model() == SkinTextures.Model.SLIM,
    ).apply {
        child = false // entity models start out as babies until an entity sets this
        // A relaxed stance instead of arms glued to the sides.
        leftArm.roll = -0.12f; rightArm.roll = 0.12f
        leftSleeve.copyTransform(leftArm); rightSleeve.copyTransform(rightArm)
    }

    /**
     * Draw the player standing in the [w]×[h] box at ([x], [y]), turned [yaw] degrees from facing
     * the viewer, wearing [loadout] (slot → item id).
     */
    fun draw(context: DrawContext, x: Int, y: Int, w: Int, h: Int, yaw: Float, loadout: Map<Slot, String>) {
        val matrices = context.matrices
        matrices.push()
        // Model space (blocks, y down like the GUI): head top at -0.5, feet at +1.5; tall hats reach about
        // -1.15, so the box is fitted to that whole span.
        val scale = h * 0.94f / 2.65f
        matrices.translate(x + w / 2f, y + h * 0.03f + 1.15f * scale, 100f)
        matrices.scale(scale, scale, scale)
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-8f))
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f + yaw)) // the model's front is -z
        DiffuseLighting.method_34742()
        val consumers = context.vertexConsumers
        val light = LightmapTextureManager.MAX_LIGHT_COORDINATE
        model.render(matrices, consumers.getBuffer(RenderLayer.getEntityTranslucent(skin.texture())), light, OverlayTexture.DEFAULT_UV, -1)
        val ticks = (System.currentTimeMillis() % 1_000_000L) / 50f
        val buffer = consumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE))
        for ((slot, id) in loadout) {
            if (slot == Slot.BADGE) continue
            val piece = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.push()
            (if (slot == Slot.BACK) model.body else model.head).rotate(matrices)
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f)
            VoxelMesh.animate(piece, ticks, matrices)
            VoxelMesh.emit(matrices.peek(), buffer, piece, light, OverlayTexture.DEFAULT_UV)
            matrices.pop()
        }
        context.draw()
        DiffuseLighting.enableGuiDepthLighting()
        matrices.pop()
    }

    companion object {
        private val WHITE: Identifier = Identifier.of("jukz", "textures/cosmetics/white.png")
    }
}
