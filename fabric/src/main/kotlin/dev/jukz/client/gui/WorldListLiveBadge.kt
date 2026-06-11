package dev.jukz.client.gui

import dev.jukz.client.JoinCoordinator
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.discovery.WorldRegistry
import dev.jukz.core.model.WorldId
import dev.jukz.discovery.Discovery
import dev.jukz.world.WorldIdSidecar
import kotlinx.coroutines.runBlocking
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws a "live" badge on each vanilla world-list row whose `jukz.dat` world is currently hosted, and
 * turns a click on that badge into a direct join (F4-C). The frequently-called render path never does
 * network I/O: discovery results are cached per world for [CACHE_MS], refreshed by at most one
 * background lookup, and the render just reads the last known record.
 *
 * The cache/refresh logic ([ensureFresh] / [cachedRecord]) is Minecraft-free and unit-tested; the
 * draw + hit-test helpers touch Minecraft and are validated in-game.
 */
object WorldListLiveBadge {

    const val CACHE_MS = 10_000L
    private const val RADIUS = 6
    private val COLOR_LIVE = 0xFF6BCB6B.toInt() // matches HostInfoScreen's live green

    private data class Cached(val record: WorldRecord?, val at: Long)

    private val cache = ConcurrentHashMap<WorldId, Cached>()
    private val querying = ConcurrentHashMap.newKeySet<WorldId>()
    private val worldIds = ConcurrentHashMap<String, Optional<WorldId>>()
    private val badgeBounds = ConcurrentHashMap<WorldId, IntArray>() // worldId -> [x1, y1, x2, y2]

    private val DEFAULT_RUNNER: (Runnable) -> Unit = { r ->
        Thread(r, "jukz-badge-lookup").apply { isDaemon = true }.start()
    }

    // ---- cache (Minecraft-free, unit-tested) ---------------------------------------------------

    /** The last known live record for [worldId], or null when none / not yet looked up. Never blocks. */
    fun cachedRecord(worldId: WorldId): WorldRecord? = cache[worldId]?.record

    /**
     * Ensure the cache for [worldId] is fresh (< [CACHE_MS] old), scheduling at most one background
     * lookup. Returns true if a lookup was scheduled (used by tests to assert the de-dup). [run]
     * executes the lookup off the caller's thread in prod, inline in tests; [now] is the clock.
     */
    fun ensureFresh(
        worldId: WorldId,
        registry: WorldRegistry,
        now: () -> Long = { System.currentTimeMillis() },
        run: (Runnable) -> Unit = DEFAULT_RUNNER,
    ): Boolean {
        val cached = cache[worldId]
        val fresh = cached != null && now() - cached.at < CACHE_MS
        if (fresh || !querying.add(worldId)) return false
        run {
            try {
                val record = runCatching { runBlocking { registry.lookup(worldId) } }.getOrNull()
                cache[worldId] = Cached(record, now())
            } finally {
                querying.remove(worldId)
            }
        }
        return true
    }

    /** Test hook: drop all cached state. */
    fun clear() {
        cache.clear()
        querying.clear()
        worldIds.clear()
        badgeBounds.clear()
    }

    // ---- render + click (Minecraft, in-game validated) -----------------------------------------

    /**
     * Draw the badge on a world row if its world is hosted live. Called from the entry render mixin.
     * Anchored top-LEFT (a green dot with the live player count centered below it) so it doesn't cover
     * the vanilla last-played line that sits on the right under the folder name.
     */
    fun render(context: DrawContext, textRenderer: TextRenderer, levelName: String, entryX: Int, entryY: Int, entryWidth: Int) {
        val worldId = worldIdFor(levelName) ?: return
        ensureFresh(worldId, Discovery.registry)
        val record = cachedRecord(worldId) ?: return

        val cxp = entryX + -8
        val cyp = entryY + 9
        fillCircle(context, cxp, cyp, RADIUS, COLOR_LIVE)

        val label = record.playerCount.toString()
        val labelX = cxp - textRenderer.getWidth(label) / 2
        val labelY = cyp + RADIUS + 2
        context.drawTextWithShadow(textRenderer, label, labelX, labelY, COLOR_LIVE)

        badgeBounds[worldId] = intArrayOf(cxp - RADIUS, cyp - RADIUS, cxp + RADIUS + 1, labelY + textRenderer.fontHeight)
    }

    /** If the click landed on a live world's badge, start a direct join and return true. */
    fun handleClick(levelName: String, mouseX: Double, mouseY: Double, parent: Screen?): Boolean {
        val worldId = worldIdFor(levelName) ?: return false
        if (cachedRecord(worldId) == null) return false
        val b = badgeBounds[worldId] ?: return false
        val hit = mouseX >= b[0] && mouseX <= b[2] && mouseY >= b[1] && mouseY <= b[3]
        if (!hit) return false
        JoinCoordinator.start(worldId, worldId.shortCode(), parent)
        return true
    }

    /** Resolve (and cache) the jukz world id for a save folder, or null if it is not a jukz world. */
    private fun worldIdFor(levelName: String): WorldId? =
        worldIds.getOrPut(levelName) {
            Optional.ofNullable(
                runCatching {
                    val saveRoot = MinecraftClient.getInstance().levelStorage.savesDirectory.resolve(levelName)
                    WorldIdSidecar.read(saveRoot)?.let { WorldId.of(it.worldId) }
                }.getOrNull(),
            )
        }.orElse(null)

    /** A filled disc, drawn scanline by scanline since DrawContext has no circle primitive. */
    private fun fillCircle(context: DrawContext, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) {
            val dx = Math.sqrt((r * r - dy * dy).toDouble()).toInt()
            context.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color)
        }
    }
}
