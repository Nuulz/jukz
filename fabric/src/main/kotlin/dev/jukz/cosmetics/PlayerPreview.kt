package dev.jukz.cosmetics

//? if >=26.2 {
/*import com.mojang.authlib.GameProfile
import dev.jukz.compat.Identifier
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.jukz.compat.GuiGraphics
import dev.jukz.compat.id
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.Minecraft
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.entity.player.PlayerModelType
import net.minecraft.world.entity.player.PlayerSkin

/**
 * A player model in a GUI wearing a loadout, no entity or world needed, so it works on the title
 * screen too. Draws the vanilla player model with [profile]'s skin, then each 3D piece on its bone
 * exactly as [CosmeticsFeatureRenderer] does in the world. (26.x: both are submitted into the picture
 * that [GuiModelRenderer] draws, like an entity's layers.)
 */
class PlayerPreview(private val profile: GameProfile, private val override: Pair<Identifier, Boolean>? = null) {
    private val client = Minecraft.getInstance()
    private val skin: PlayerSkin = client.skinManager.createLookup(profile, false).get()
    private val slim = override?.second ?: (skin.model() == PlayerModelType.SLIM)
    private val texture = override?.first ?: skin.body().texturePath()
    private val model = PlayerModel(client.entityModels.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER), slim)
    private val pose = AvatarRenderState() // a player standing still; the model is posed from it when drawn

    /**
     * Draw the player standing in the [w]×[h] box at ([x], [y]), turned [yaw] degrees from facing
     * the viewer, wearing [loadout] (slot → item id).
     */
    fun draw(context: GuiGraphics, x: Int, y: Int, w: Int, h: Int, yaw: Float, loadout: Map<Slot, String>) {
        // Model space is in blocks: head top at -0.5, feet at +1.5; tall hats reach about -1.15, so the
        // box is fitted to that whole span. The picture's origin is its bottom centre.
        val scale = h * 0.94f / 2.65f
        GuiModelRenderer.submit(context, x, y, x + w, y + h, scale) { matrices, collector ->
            matrices.translate(0f, -1.585f, 0f) // feet a little above the bottom edge
            matrices.mulPose(Axis.XP.rotationDegrees(-8f))
            matrices.mulPose(Axis.YP.rotationDegrees(yaw)) // the picture flips z, so no half turn to face us
            drawModel(matrices, collector, loadout)
        }
    }

    private fun drawModel(matrices: PoseStack, collector: SubmitNodeCollector, loadout: Map<Slot, String>) {
        val light = LightCoordsUtil.FULL_BRIGHT
        collector.submitModel(model, pose, matrices, texture, light, OverlayTexture.NO_OVERLAY, 0, null)
        val ticks = (System.currentTimeMillis() % 1_000_000L) / 50f
        val renderType = RenderTypes.entityTranslucent(WHITE)
        for ((slot, itemId) in loadout) {
            if (slot == Slot.BADGE) continue
            val piece = Cosmetics.catalog.item(itemId)?.model ?: continue
            matrices.pushPose()
            (if (slot == Slot.BACK) model.body else model.head).translateAndRotate(matrices)
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f)
            CosmeticFit.apply(matrices, slot, Cosmetics.myFit(slot))
            VoxelMesh.animate(piece, ticks, matrices)
            collector.submitCustomGeometry(matrices, renderType) { p, buffer -> VoxelMesh.emit(p, buffer, piece, light, OverlayTexture.NO_OVERLAY, pose = VoxelMesh.Pose(ticks)) }
            matrices.popPose()
        }
    }

    companion object {
        private val WHITE = id("textures/cosmetics/white.png")
    }
}
*///?} else if >=1.21.11 {
/*import com.mojang.authlib.GameProfile
import dev.jukz.compat.Identifier
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.jukz.compat.id
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.Minecraft
import dev.jukz.compat.GuiGraphics
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.entity.player.PlayerModelType
import net.minecraft.world.entity.player.PlayerSkin

/**
 * A player model in a GUI wearing a loadout, no entity or world needed, so it works on the title
 * screen too. Draws the vanilla player model with [profile]'s skin, then each 3D piece on its bone
 * exactly as [CosmeticsFeatureRenderer] does in the world. (1.21.6+: 3D in a GUI is drawn into its own
 * texture by [GuiModelRenderer], then placed on the screen.)
 */
class PlayerPreview(private val profile: GameProfile, private val override: Pair<Identifier, Boolean>? = null) {
    private val client = Minecraft.getInstance()
    private val skin: PlayerSkin = client.skinManager.createLookup(profile, false).get()
    private val slim = override?.second ?: (skin.model() == PlayerModelType.SLIM)
    private val texture = override?.first ?: skin.body().texturePath()
    private val model = PlayerModel(client.entityModels.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER), slim).apply {
        // A relaxed stance instead of arms glued to the sides (the sleeves are the arms' children).
        leftArm.zRot = -0.12f; rightArm.zRot = 0.12f
    }

    /**
     * Draw the player standing in the [w]×[h] box at ([x], [y]), turned [yaw] degrees from facing
     * the viewer, wearing [loadout] (slot → item id).
     */
    fun draw(context: GuiGraphics, x: Int, y: Int, w: Int, h: Int, yaw: Float, loadout: Map<Slot, String>) {
        // Model space is in blocks: head top at -0.5, feet at +1.5; tall hats reach about -1.15, so the
        // box is fitted to that whole span. The picture's origin is its bottom centre.
        val scale = h * 0.94f / 2.65f
        GuiModelRenderer.submit(context, x, y, x + w, y + h, scale) { matrices, buffers ->
            matrices.translate(0f, -1.585f, 0f) // feet a little above the bottom edge
            matrices.mulPose(Axis.XP.rotationDegrees(-8f))
            matrices.mulPose(Axis.YP.rotationDegrees(yaw)) // the picture flips z, so no half turn to face us
            drawModel(matrices, buffers, loadout)
        }
    }

    private fun drawModel(matrices: PoseStack, buffers: MultiBufferSource, loadout: Map<Slot, String>) {
        val light = LightTexture.FULL_BRIGHT
        model.renderToBuffer(matrices, buffers.getBuffer(RenderTypes.entityTranslucent(texture)), light, OverlayTexture.NO_OVERLAY)
        val ticks = (System.currentTimeMillis() % 1_000_000L) / 50f
        val buffer = buffers.getBuffer(RenderTypes.entityTranslucent(WHITE))
        for ((slot, itemId) in loadout) {
            if (slot == Slot.BADGE) continue
            val piece = Cosmetics.catalog.item(itemId)?.model ?: continue
            matrices.pushPose()
            (if (slot == Slot.BACK) model.body else model.head).translateAndRotate(matrices)
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f)
            CosmeticFit.apply(matrices, slot, Cosmetics.myFit(slot))
            VoxelMesh.animate(piece, ticks, matrices)
            VoxelMesh.emit(matrices.last(), buffer, piece, light, OverlayTexture.NO_OVERLAY, pose = VoxelMesh.Pose(ticks))
            matrices.popPose()
        }
    }

    companion object {
        private val WHITE = id("textures/cosmetics/white.png")
    }
}
*///?} else {
import com.mojang.authlib.GameProfile
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.Minecraft
import dev.jukz.compat.GuiGraphics
import net.minecraft.client.player.AbstractClientPlayer
import com.mojang.blaze3d.platform.Lighting
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.model.PlayerModel
import net.minecraft.client.resources.PlayerSkin
import dev.jukz.compat.Identifier
import com.mojang.math.Axis

/**
 * A player model in a GUI wearing a loadout — no entity or world needed, so it works on the title
 * screen too. Draws the vanilla player model with [profile]'s skin, then each 3D piece on its bone
 * exactly as [CosmeticsFeatureRenderer] does in the world.
 */
class PlayerPreview(private val profile: GameProfile, private val override: Pair<Identifier, Boolean>? = null) {
    private val client = Minecraft.getInstance()
    private val skin: PlayerSkin = client.skinManager.getInsecureSkin(profile)
    private val slim = override?.second ?: (skin.model() == PlayerSkin.Model.SLIM)
    private val texture = override?.first ?: skin.texture()
    private val model = PlayerModel<AbstractClientPlayer>(
        client.entityModels.bakeLayer(if (slim) ModelLayers.PLAYER_SLIM else ModelLayers.PLAYER),
        slim,
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
        model.renderToBuffer(matrices, consumers.getBuffer(RenderType.entityTranslucent(texture)), light, OverlayTexture.NO_OVERLAY, -1)
        val ticks = (System.currentTimeMillis() % 1_000_000L) / 50f
        val buffer = consumers.getBuffer(RenderType.entityTranslucent(WHITE))
        for ((slot, id) in loadout) {
            if (slot == Slot.BADGE) continue
            val piece = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.pushPose()
            (if (slot == Slot.BACK) model.body else model.head).translateAndRotate(matrices)
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f)
            CosmeticFit.apply(matrices, slot, Cosmetics.myFit(slot))
            VoxelMesh.animate(piece, ticks, matrices)
            VoxelMesh.emit(matrices.last(), buffer, piece, light, OverlayTexture.NO_OVERLAY, pose = VoxelMesh.Pose(ticks))
            matrices.popPose()
        }
        context.flush()
        Lighting.setupFor3DItems()
        matrices.popPose()
    }

    companion object {
        private val WHITE: Identifier = Identifier.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")
    }
}
//?}
