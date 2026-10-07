package dev.jukz.preview

import dev.jukz.cosmetics.CosmeticCatalog.Slot
import dev.jukz.cosmetics.Cosmetics
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.CameraType
import java.nio.file.Files
import java.nio.file.Path

/**
 * Films cosmetics in the real game, for cosmetics/tools/preview-in-game.sh (read that first; it sets the
 * jukz.preview.* properties). Wears the items, then for each camera (front, back) takes one screenshot
 * per game tick; if an emote is worn it is played from the first frame. Emotes run on a game-tick clock
 * here, so frames are evenly spaced no matter how slow screenshots are.
 */
object CosmeticPreview : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val items = System.getProperty("jukz.preview.items").orEmpty().split(',').filter { it.isNotBlank() }
        val out = Path.of(System.getProperty("jukz.preview.out") ?: error("jukz.preview.out not set"))
        val frames = System.getProperty("jukz.preview.frames")?.toInt() ?: 60
        Files.createDirectories(out)

        var ticks = 0L
        context.runOnClient<RuntimeException> { _ ->
            val loadout = items.associateBy { id -> Cosmetics.catalog.item(id)?.slot ?: error("unknown item $id") }
            Cosmetics.previewLoadout = loadout
            Cosmetics.clock = { ticks * 50 }
        }
        val emote = items.any { Cosmetics.catalog.item(it)?.slot == Slot.EMOTE }

        context.worldBuilder().create().use { world ->
            //? if >=26.1 {
            /*world.connection.waitForChunksRender()
            *///?} else {
            world.clientWorld.waitForChunksRender()
            //?}
            context.runOnClient<RuntimeException> { mc ->
                //? if >=26.1 {
                /*if (!mc.gui.hud.isHidden) mc.gui.hud.toggle()
                *///?} else {
                mc.options.hideGui = true
                //?}
                mc.player!!.apply { setYRot(0f); setXRot(10f); yBodyRot = 0f; yHeadRot = 0f }
            }
            context.waitTicks(20)
            for ((view, camera) in listOf("front" to CameraType.THIRD_PERSON_FRONT, "back" to CameraType.THIRD_PERSON_BACK)) {
                context.runOnClient<RuntimeException> { mc -> mc.options.setCameraType(camera) }
                context.waitTicks(5)
                if (emote) context.runOnClient<RuntimeException> { _ -> Cosmetics.emote() }
                for (i in 0 until frames) {
                    context.takeScreenshot(
                        TestScreenshotOptions.of("%s_%03d".format(view, i)).disableCounterPrefix().withSize(480, 480)
                            //? if >=26.1 {
                            /*.withDeltaTicks(0f)
                            *///?} else {
                            .withTickDelta(0f)
                            //?}
                            .withDestinationDir(out),
                    )
                    context.waitTick()
                    ticks++
                }
            }
        }
        context.runOnClient<RuntimeException> { _ -> Cosmetics.previewLoadout = null; Cosmetics.clock = System::currentTimeMillis }
    }
}
