package dev.jukz.cosmetics

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
import net.minecraft.resources.ResourceLocation

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
        if (loadout.size <= 1 && Slot.BADGE in loadout) return
        val buffer = vertexConsumers.getBuffer(RenderType.entityTranslucent(WHITE))
        val overlay = LivingEntityRenderer.getOverlayCoords(entity, 0f)
        for ((slot, id) in loadout) {
            if (slot == Slot.BADGE || hiddenBy(slot, entity)) continue
            val model = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.pushPose()
            val bone = if (slot == Slot.BACK) parentModel.body else parentModel.head
            bone.translateAndRotate(matrices)
            matrices.scale(PIXEL, PIXEL, PIXEL) // bone space is in blocks; models are in skin pixels
            VoxelMesh.animate(model, animationProgress, matrices)
            VoxelMesh.emit(matrices.last(), buffer, model, light, overlay)
            matrices.popPose()
        }
    }

    private fun hiddenBy(slot: Slot, entity: AbstractClientPlayer): Boolean = when (slot) {
        Slot.HAT, Slot.FACE -> !entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty
        Slot.BACK -> entity.getItemBySlot(EquipmentSlot.CHEST).`is`(Items.ELYTRA)
        Slot.BADGE -> true
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private val WHITE: ResourceLocation = ResourceLocation.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")
    }
}
