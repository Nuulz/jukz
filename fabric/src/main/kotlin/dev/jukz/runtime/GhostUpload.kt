package dev.jukz.runtime

import dev.jukz.core.model.WorldId

/**
 * Hand-off between the server-stopping hook and the client upload screen for the ghost-takeover
 * upload. The hook (server thread) builds the pack and [arm]s this holder; the [UploadingWorldScreen]
 * (client thread) polls [pending], uploads it to R2, then [clear]s. Kept dependency-free (only `core`
 * + ByteArray) so it loads anywhere.
 *
 * `armed` is a cheap, set-early flag the disconnect mixin reads to decide whether to show the upload
 * screen *before* the pack itself is ready (the pack is built slightly later, once the server has
 * finished stopping). The screen then waits for [pending] to appear.
 */
object GhostUpload {

    data class Pending(
        val worldId: WorldId,
        val generation: Long,
        val pack: ByteArray,
        val head: String,
    )

    @Volatile
    private var armed: Boolean = false

    @Volatile
    private var pending: Pending? = null

    /** Arm the upload (equivalent to [setArmed]`(true)`); kept for call sites that only ever arm. */
    fun markArmed() = setArmed(true)

    /**
     * Set whether this world close may back up to the cloud — true when the world is eligible
     * (rendezvous configured, access open), regardless of any connected guest. A leaving host prefers a
     * live P2P handoff when guests are present and only uploads when that handoff reaches nobody, so
     * this is set per close (from JukzMod) so a previous world's decision never leaks into the next.
     */
    fun setArmed(value: Boolean) {
        armed = value
    }

    /** True when this close may upload (a handoff that reached nobody, or a guest-less close). */
    fun isArmed(): Boolean = armed

    /**
     * Publish the built pack for the screen to upload. Also sets [armed] so the holder's invariant
     * ("a pending pack is always armed") holds even if a caller publishes a pack without a prior
     * [markArmed] — the disconnect mixin keys the upload screen off [isArmed].
     */
    fun arm(pending: Pending) {
        this.pending = pending
        armed = true
    }

    /** The built pack, or null until the hook has produced it. */
    fun pending(): Pending? = pending

    /** Drop all state once the upload completes (or the player bails out). */
    fun clear() {
        armed = false
        pending = null
    }
}
