package dev.jukz.cosmetics

import com.mojang.authlib.GameProfile
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.player.AbstractClientPlayer
import com.mojang.blaze3d.platform.Lighting
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import com.mojang.math.Axis

/**
 * A player model in a GUI wearing a loadout — no entity or world needed, so it works on the title
 * screen too. Draws the vanilla player model with [profile]'s skin, then each 3D piece on its bone
 * exactly as [CosmeticsFeatureRenderer] does in the world.
 */
class PlayerPreview(private val profile: GameProfile) {
    private val client = Minecraft.getInstance()
    private val skin: PlayerSkin = client.skinManager.getInsecureSkin(profile)
    private val model = PlayerModel<AbstractClientPlayer>(
        client.entityModels.bakeLayer(if (skin.model() == PlayerSkin.Model.SLIM) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER),
        skin.model() == PlayerSkin.Model.SLIM,
    ).apply {
        young = false // entity models start out as babies until an entity sets this
        // A relaxed stance instead of arms glued to the sides.
        leftArm.zRot = -0.12f; rightArm.zRot = 0.12f
        leftSleeve.copyFrom(leftArm); rightSleeve.copyFrom(rightArm)
    }

    /**
     * Draw the player standing in the [w]×[h] box at ([x], [y]), turned [yaw] degrees from facing
     * the viewer, wearing [loadout] (slot → item id).
     */
    fun draw(context: GuiGraphics, x: Int, y: Int, w: Int, h: Int, yaw: Float, loadout: Map<Slot, String>) {
        val matrices = context.pose()
        matrices.pushPose()
        // Model space (blocks, y down like the GUI): head top at -0.5, feet at +1.5; tall hats reach about
        // -1.15, so the box is fitted to that whole span.
        val scale = h * 0.94f / 2.65f
        matrices.translate(x + w / 2f, y + h * 0.03f + 1.15f * scale, 100f)
        matrices.scale(scale, scale, scale)
        matrices.mulPose(Axis.XP.rotationDegrees(-8f))
        matrices.mulPose(Axis.YP.rotationDegrees(180f + yaw)) // the model's front is -z
        Lighting.setupForEntityInInventory()
        val consumers = context.bufferSource()
        val light = LightTexture.FULL_BRIGHT
        model.renderToBuffer(matrices, consumers.getBuffer(RenderType.entityTranslucent(skin.texture())), light, OverlayTexture.NO_OVERLAY, -1)
        val ticks = (System.currentTimeMillis() % 1_000_000L) / 50f
        val buffer = consumers.getBuffer(RenderType.entityTranslucent(WHITE))
        for ((slot, id) in loadout) {
            if (slot == Slot.BADGE) continue
            val piece = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.pushPose()
            (if (slot == Slot.BACK) model.body else model.head).translateAndRotate(matrices)
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f)
            VoxelMesh.animate(piece, ticks, matrices)
            VoxelMesh.emit(matrices.last(), buffer, piece, light, OverlayTexture.NO_OVERLAY)
            matrices.popPose()
        }
        context.flush()
        Lighting.setupFor3DItems()
        matrices.popPose()
    }

    companion object {
        private val WHITE: ResourceLocation = ResourceLocation.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")
    }
}
