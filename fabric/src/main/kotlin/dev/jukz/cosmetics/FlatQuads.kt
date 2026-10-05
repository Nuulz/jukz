package dev.jukz.cosmetics

//? if >=1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.render.state.GuiElementRenderState
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix3x2f

/** Flat-coloured 2D quads as one GUI element (1.21.6+), already sorted back to front. */
object FlatQuads {
    class Face(val xs: FloatArray, val ys: FloatArray, val depth: Float, val argb: Int)

    private class Element(
        private val faces: List<Face>,
        private val pose: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle?,
    ) : GuiElementRenderState {
        override fun buildVertices(consumer: VertexConsumer) {
            for (f in faces) for (i in 0 until 4) consumer.addVertexWith2DPose(pose, f.xs[i], f.ys[i]).setColor(f.argb)
        }
        override fun pipeline(): RenderPipeline = RenderPipelines.GUI
        override fun textureSetup(): TextureSetup = TextureSetup.noTexture()
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = bounds
    }

    fun submit(context: GuiGraphics, faces: List<Face>, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (faces.isEmpty()) return
        val pose = Matrix3x2f(context.pose())
        val scissor = context.scissorStack.peek()
        val area = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
        context.guiRenderState.submitGuiElement(Element(faces, pose, scissor, scissor?.intersection(area) ?: area))
    }
}
*///?}
