package dev.jukz.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.OverlayTexture
//? if >=26.2 {
/*import dev.jukz.compat.id
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.renderer.rendertype.RenderTypes

/** Trails are drawn with the world, so you see your own in first person too. */
object TrailRenderer {
    private val WHITE = id("textures/cosmetics/white.png")

    fun register() {
        LevelRenderEvents.COLLECT_SUBMITS.register { ctx ->
            val client = Minecraft.getInstance()
            val cam = client.gameRenderer.mainCamera().position()
            val matrices = PoseStack()
            val type = RenderTypes.entityTranslucent(WHITE)
            Trails.draw(matrices, cam.x, cam.y, cam.z, client.deltaTracker.getGameTimeDeltaPartialTick(false)) { model, opacity ->
                ctx.submitNodeCollector().submitCustomGeometry(matrices, type) { p, buffer -> VoxelMesh.emit(p, buffer, model, VoxelMesh.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, opacity) }
            }
        }
    }
}
*///?} else if >=1.21.11 {
/*import dev.jukz.compat.id
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.minecraft.client.renderer.rendertype.RenderTypes

/** Trails are drawn with the world, so you see your own in first person too. */
object TrailRenderer {
    private val WHITE = id("textures/cosmetics/white.png")

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register { ctx ->
            val client = Minecraft.getInstance()
            val cam = client.gameRenderer.mainCamera.position()
            val matrices = PoseStack()
            val type = RenderTypes.entityTranslucent(WHITE)
            Trails.draw(matrices, cam.x, cam.y, cam.z, client.deltaTracker.getGameTimeDeltaPartialTick(false)) { model, opacity ->
                ctx.commandQueue().submitCustomGeometry(matrices, type) { p, buffer -> VoxelMesh.emit(p, buffer, model, VoxelMesh.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, opacity) }
            }
        }
    }
}
*///?} else {
import dev.jukz.compat.Identifier
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents
import net.minecraft.client.renderer.RenderType

/** Trails are drawn with the world, so you see your own in first person too. */
object TrailRenderer {
    private val WHITE: Identifier = Identifier.fromNamespaceAndPath("jukz", "textures/cosmetics/white.png")

    fun register() {
        WorldRenderEvents.AFTER_ENTITIES.register { _ ->
            val client = Minecraft.getInstance()
            val cam = client.gameRenderer.mainCamera.position
            val matrices = PoseStack()
            val type = RenderType.entityTranslucent(WHITE)
            val source = client.renderBuffers().bufferSource()
            val buffer = source.getBuffer(type)
            Trails.draw(matrices, cam.x, cam.y, cam.z, client.timer.getGameTimeDeltaPartialTick(false)) { model, opacity ->
                VoxelMesh.emit(matrices.last(), buffer, model, VoxelMesh.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, opacity)
            }
            source.endBatch(type)
        }
    }
}
//?}
