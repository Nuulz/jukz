package dev.jukz.cosmetics

import com.mojang.blaze3d.vertex.PoseStack
import dev.jukz.cosmetics.CosmeticCatalog.Slot

/**
 * Where a player moved a worn piece, in skin pixels (like Lunar's cosmetic positions): [up] raises it,
 * [out] moves it away from the body (forward for hats and face pieces, backward for back pieces).
 * Each player sets theirs in the hub; it travels with their loadout over the game connection.
 */
data class CosmeticFit(val up: Float = 0f, val out: Float = 0f) {
    val isZero: Boolean get() = up == 0f && out == 0f

    fun encode(): String = "${fmt(up)},${fmt(out)}"

    companion object {
        const val STEP = 0.5f
        const val LIMIT = 6f
        val NONE = CosmeticFit()

        fun decode(text: String): CosmeticFit? {
            val parts = text.split(',')
            if (parts.size != 2) return null
            val up = parts[0].toFloatOrNull() ?: return null
            val out = parts[1].toFloatOrNull() ?: return null
            return CosmeticFit(clamp(up), clamp(out))
        }

        fun clamp(v: Float): Float = (Math.round(v.coerceIn(-LIMIT, LIMIT) / STEP) * STEP)

        /** Move the pose (already in bone pixel space: y points down, z forward is negative) by [fit]. */
        fun apply(matrices: PoseStack, slot: Slot, fit: CosmeticFit) {
            if (fit.isZero) return
            val z = if (slot.onBody) fit.out else -fit.out
            matrices.translate(0f, -fit.up, z)
        }

        private fun fmt(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
    }
}
