package dev.jukz.client.web

import com.mojang.blaze3d.platform.NativeImage
import dev.jukz.compat.GuiGraphics
import dev.jukz.compat.Identifier
import dev.nuulz.pane.PaneView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * A [PaneView]'s pixels as a Minecraft texture: when the page painted, its BGRA (plus an open
 * dropdown) goes into a [NativeImage] as ABGR and is uploaded. Only the game's own texture API, so it
 * draws the same on every version.
 */
class BrowserTexture {
    private val id = Identifier.fromNamespaceAndPath("jukz", "browser/${serial++}")
    private var texture: DynamicTexture? = null
    private var width = 0
    private var height = 0
    private var version = -1

    /** Re-uploads if the page changed; false while there's nothing to draw yet. */
    fun update(page: PaneView): Boolean {
        val pixels = page.pixels() ?: return texture != null
        val w = page.width()
        val h = page.height()
        if (w != width || h != height || texture == null) {
            release()
            width = w; height = h
            //? if >=1.21.11 {
            /*texture = DynamicTexture({ id.toString() }, w, h, false)
            *///?} else {
            texture = DynamicTexture(w, h, false)
            //?}
            Minecraft.getInstance().textureManager.register(id, texture!!)
            version = -1
        }
        if (page.version() == version) return true
        version = page.version()
        val image = texture!!.pixels ?: return false
        copy(pixels, w, 0, 0, w, h, image)
        val popup = page.popupPixels()
        val at = page.popupBounds()
        if (popup != null && at != null) copy(popup, at.width, at.x, at.y, at.width, at.height, image)
        texture!!.upload()
        return true
    }

    /** BGRA rows ([srcWidth] wide) into the image at ([x], [y]), clipped, as opaque ABGR. */
    private fun copy(src: ByteBuffer, srcWidth: Int, x: Int, y: Int, w: Int, h: Int, image: NativeImage) {
        val base = MemoryUtil.memAddress(src)
        val cols = minOf(w, width - x)
        //? if >=1.21.11 {
        /*val dst = image.pointer
        *///?}
        for (row in 0 until minOf(h, height - y)) {
            if (y + row < 0) continue
            var s = base + row.toLong() * srcWidth * 4
            //? if >=1.21.11 {
            /*var d = dst + ((y + row).toLong() * width + x) * 4
            *///?}
            for (col in 0 until cols) {
                val v = MemoryUtil.memGetInt(s)
                val abgr = (v and 0x0000FF00) or ((v ushr 16) and 0xFF) or ((v and 0xFF) shl 16) or (0xFF shl 24)
                //? if >=1.21.11 {
                /*MemoryUtil.memPutInt(d, abgr); d += 4
                *///?} else {
                image.setPixelRGBA(x + col, y + row, abgr) // 1.21.1 doesn't expose the pointer
                //?}
                s += 4
            }
        }
    }

    fun draw(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        if (texture == null) return
        //? if >=1.21.11 {
        /*g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, w, h, width, height, width, height)
        *///?} else {
        g.blit(id, x, y, w, h, 0f, 0f, width, height, width, height)
        //?}
    }

    fun release() {
        if (texture != null) Minecraft.getInstance().textureManager.release(id)
        texture = null
    }

    private companion object {
        var serial = 0
    }
}
