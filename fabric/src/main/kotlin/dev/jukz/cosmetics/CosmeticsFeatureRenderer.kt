package dev.jukz.cosmetics

//? if >=1.21.11 {
/*import dev.jukz.compat.id
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.item.Items

/**
 * Draws the 3D cosmetics (hat, face, back) on every player model: in the world, in third person and in
 * inventory screens. Each piece is attached to its bone (the head, or the body for back pieces), so it
 * follows looking around and sneaking. Pieces are plain coloured voxels on a white texture, lit like the
 * rest of the player. A helmet hides the hat and face pieces, and elytra the back piece, so they never
 * poke through armour. (1.21.9+: layers submit geometry from the render state instead of drawing.)
 */
class CosmeticsFeatureRenderer(
    context: RenderLayerParent<AvatarRenderState, PlayerModel>,
) : RenderLayer<AvatarRenderState, PlayerModel>(context) {

    override fun submit(matrices: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
        if (state.isInvisible) return
        // The render state only carries the entity id; mannequins and other avatars aren't players.
        val player = Minecraft.getInstance().level?.getEntity(state.id) as? AbstractClientPlayer ?: return
        val loadout = Cosmetics.loadoutFor(player.uuid)
        val emote = Cosmetics.emoting(player.uuid)
        if (loadout.keys.all { it.flat } && emote == null) return
        val overlay = LivingEntityRenderer.getOverlayCoords(state, 0f)
        val renderType = RenderTypes.entityTranslucent(WHITE)
        val glow: (CosmeticCatalog.Model, Float) -> Unit = { model, opacity ->
            collector.submitCustomGeometry(matrices, renderType) { p, buffer -> VoxelMesh.emit(p, buffer, model, VoxelMesh.FULL_BRIGHT, overlay, opacity) }
        }
        emote?.let { Trails.drawEmote(matrices, it, glow) }
        val pose = VoxelMesh.Pose(state.ageInTicks, state.walkAnimationSpeed, state.isCrouching)
        for ((slot, itemId) in loadout) {
            if (slot.flat || hiddenBy(slot, state)) continue
            val model = Cosmetics.catalog.item(itemId)?.model ?: continue
            matrices.pushPose()
            val bone = if (slot.onBody) parentModel.body else parentModel.head
            bone.translateAndRotate(matrices)
            matrices.scale(PIXEL, PIXEL, PIXEL) // bone space is in blocks; models are in skin pixels
            CosmeticFit.apply(matrices, slot, Cosmetics.fitFor(player.uuid, slot))
            VoxelMesh.animate(model, state.ageInTicks, matrices)
            collector.submitCustomGeometry(matrices, renderType) { p, buffer -> VoxelMesh.emit(p, buffer, model, light, overlay, pose = pose) }
            matrices.popPose()
        }
    }

    private fun hiddenBy(slot: Slot, state: AvatarRenderState): Boolean = when (slot) {
        Slot.HAT, Slot.FACE -> !state.headEquipment.isEmpty
        Slot.BACK -> state.chestEquipment.`is`(Items.ELYTRA)
        else -> slot.flat
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private val WHITE = id("textures/cosmetics/white.png")
    }
}
*///?} else {
import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.model.PlayerModel
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.Items
import dev.jukz.compat.Identifier

/**
 * Draws the 3D cosmetics (hat, face, back) on every player model — in the world, in third person and in
 * inventory/preview screens. Each piece is attached to its bone (the head, or the body for back pieces),
 * so it follows looking around and sneaking. Pieces are plain coloured voxels on a white texture, lit
 * like the rest of the player. A helmet hides the hat and face pieces, and elytra the back piece, so
 * they never poke through armour.
 */
class CosmeticsFeatureRenderer(
    context: RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>,
) : RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>>(context) {

    override fun render(
        matrices: PoseStack,
        vertexConsumers: MultiBufferSource,
        light: Int,
        entity: AbstractClientPlayer,
        limbAngle: Float,
        limbDistance: Float,
        tickDelta: Float,
        animationProgress: Float,
        headYaw: Float,
        headPitch: Float,
    ) {
        if (entity.isInvisible) return
        val loadout = Cosmetics.loadoutFor(entity.uuid)
        val emote = Cosmetics.emoting(entity.uuid)
        if (loadout.keys.all { it.flat } && emote == null) return
        val buffer = vertexConsumers.getBuffer(RenderType.entityTranslucent(WHITE))
        val overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f)
        val glow: (CosmeticCatalog.Model, Float) -> Unit = { model, opacity -> VoxelMesh.emit(matrices.last(), buffer, model, VoxelMesh.FULL_BRIGHT, overlay, opacity) }
        emote?.let { Trails.drawEmote(matrices, it, glow) }
        val pose = VoxelMesh.Pose(animationProgress, limbDistance, entity.isCrouching)
        for ((slot, id) in loadout) {
            if (slot.flat || hiddenBy(slot, entity)) continue
            val model = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.pushPose()
            val bone = if (slot.onBody) parentModel.body else parentModel.head
            bone.translateAndRotate(matrices)
            matrices.scale(PIXEL, PIXEL, PIXEL) // bone space is in blocks; models are in skin pixels
            CosmeticFit.apply(matrices, slot, Cosmetics.fitFor(entity.uuid, slot))
            VoxelMesh.animate(model, animationProgress, matrices)
            VoxelMesh.emit(matrices.last(), buffer, model, light, overlay, pose = pose)
            matrices.popPose()
        }
    }

    private fun hiddenBy(slot: Slot, entity: AbstractClientPlayer): Boolean = when (slot) {
        Slot.HAT, Slot.FACE -> !entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty
        Slot.BACK -> entity.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.ELYTRA)
        else -> slot.flat
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private val WHITE: Identifier = Identifier.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")
    }
}
//?}
