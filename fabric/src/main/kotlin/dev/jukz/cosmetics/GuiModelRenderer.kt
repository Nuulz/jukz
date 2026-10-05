package dev.jukz.cosmetics

//? if >=1.21.11 {
/*import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.MultiBufferSource

/**
 * 3D models in a GUI (1.21.6+): Minecraft draws them into a texture of their own and then places it on
 * the screen. [submit] queues one such picture; [draw] gets a pose stack whose origin is the picture's
 * bottom centre, in blocks scaled by the state's scale. One picture per frame (the player preview):
 * every picture of this kind shares the renderer's single texture.
 */
class GuiModelRenderer(buffers: MultiBufferSource.BufferSource) : PictureInPictureRenderer<GuiModelRenderer.State>(buffers) {

    class State(
        private val x0: Int, private val y0: Int, private val x1: Int, private val y1: Int,
        private val scale: Float,
        private val scissor: ScreenRectangle?,
        val draw: (PoseStack, MultiBufferSource) -> Unit,
    ) : PictureInPictureRenderState {
        private val bounds = PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor)
        override fun x0() = x0
        override fun y0() = y0
        override fun x1() = x1
        override fun y1() = y1
        override fun scale() = scale
        override fun scissorArea() = scissor
        override fun bounds() = bounds
    }

    override fun getRenderStateClass(): Class<State> = State::class.java

    override fun renderToTexture(state: State, matrices: PoseStack) {
        Minecraft.getInstance().gameRenderer.lighting.setupFor(Lighting.Entry.ENTITY_IN_UI)
        state.draw(matrices, bufferSource)
    }

    override fun getTextureLabel(): String = "jukz model"

    companion object {
        fun submit(context: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, scale: Float, draw: (PoseStack, MultiBufferSource) -> Unit) {
            context.guiRenderState.submitPicturesInPictureState(State(x0, y0, x1, y1, scale, context.scissorStack.peek(), draw))
        }
    }
}
*///?}
