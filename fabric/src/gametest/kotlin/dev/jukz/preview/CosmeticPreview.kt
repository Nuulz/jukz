package dev.jukz.preview

import dev.jukz.cosmetics.CosmeticCatalog.Slot
import dev.jukz.cosmetics.Cosmetics
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions
import net.minecraft.client.CameraType
import net.minecraft.client.KeyMapping
import net.minecraft.client.Options
import java.nio.file.Files
import java.nio.file.Path

/**
 * Films cosmetics in the real game, for cosmetics/tools/preview-in-game.sh (read that first; it sets the
 * jukz.preview.* properties). Wears the items, then for each camera (front, back) and each move (still,
 * walk, run, sneak) takes one screenshot per game tick, holding the real keys; if an emote is worn it
 * plays at the start of each camera. Emotes run on a game-tick clock here, so frames are evenly spaced
 * no matter how slow screenshots are.
 */
object CosmeticPreview : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        val items = System.getProperty("jukz.preview.items").orEmpty().split(',').filter { it.isNotBlank() }
        val out = Path.of(System.getProperty("jukz.preview.out") ?: error("jukz.preview.out not set"))
        val frames = System.getProperty("jukz.preview.frames")?.toInt() ?: 40
        val moves = System.getProperty("jukz.preview.moves").orEmpty().split(',').filter { it.isNotBlank() }
            .ifEmpty { listOf("still", "walk", "run", "sneak") }
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
                var n = 0
                for (move in moves) {
                    val keys = context.computeOnClient<List<KeyMapping>, RuntimeException> { mc -> keysFor(mc.options, move) }
                    keys.forEach { context.input.holdKey(it) }
                    repeat(frames) {
                        context.takeScreenshot(
                            TestScreenshotOptions.of("%s_%03d_%s".format(view, n++, move)).disableCounterPrefix().withSize(480, 480)
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
                    keys.forEach { context.input.releaseKey(it) }
                    context.waitTicks(10) // come to a stop before the next move
                }
            }
        }
        context.runOnClient<RuntimeException> { _ -> Cosmetics.previewLoadout = null; Cosmetics.clock = System::currentTimeMillis }
    }

    private fun keysFor(o: Options, move: String): List<KeyMapping> = when (move) {
        "still" -> emptyList()
        "walk" -> listOf(o.keyUp)
        "run" -> listOf(o.keyUp, o.keySprint)
        "sneak" -> listOf(o.keyShift)
        else -> error("unknown move $move (still, walk, run, sneak)")
    }
}
