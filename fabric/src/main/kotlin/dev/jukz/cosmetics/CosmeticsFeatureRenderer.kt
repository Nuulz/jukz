package dev.jukz.cosmetics

import dev.jukz.cosmetics.CosmeticCatalog.Slot
import net.minecraft.client.network.AbstractClientPlayerEntity
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.Items
import net.minecraft.util.Identifier

/**
 * Draws the 3D cosmetics (hat, face, back) on every player model — in the world, in third person and in
 * inventory/preview screens. Each piece is attached to its bone (the head, or the body for back pieces),
 * so it follows looking around and sneaking. Pieces are plain coloured voxels on a white texture, lit
 * like the rest of the player. A helmet hides the hat and face pieces, and elytra the back piece, so
 * they never poke through armour.
 */
class CosmeticsFeatureRenderer(
    context: FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>>,
) : FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>>(context) {

    override fun render(
        matrices: MatrixStack,
        vertexConsumers: VertexConsumerProvider,
        light: Int,
        entity: AbstractClientPlayerEntity,
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
        val buffer = vertexConsumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE))
        val overlay = LivingEntityRenderer.getOverlay(entity, 0f)
        for ((slot, id) in loadout) {
            if (slot == Slot.BADGE || hiddenBy(slot, entity)) continue
            val model = Cosmetics.catalog.item(id)?.model ?: continue
            matrices.push()
            val bone = if (slot == Slot.BACK) contextModel.body else contextModel.head
            bone.rotate(matrices)
            matrices.scale(PIXEL, PIXEL, PIXEL) // bone space is in blocks; models are in skin pixels
            VoxelMesh.animate(model, animationProgress, matrices)
            VoxelMesh.emit(matrices.peek(), buffer, model, light, overlay)
            matrices.pop()
        }
    }

    private fun hiddenBy(slot: Slot, entity: AbstractClientPlayerEntity): Boolean = when (slot) {
        Slot.HAT, Slot.FACE -> !entity.getEquippedStack(EquipmentSlot.HEAD).isEmpty
        Slot.BACK -> entity.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)
        Slot.BADGE -> true
    }

    companion object {
        private const val PIXEL = 1f / 16f
        private val WHITE: Identifier = Identifier.of("jukz", "textures/cosmetics/white.png")
    }
}
