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

    /** Set the moment we decide a ghost upload will happen (before the pack is built). */
    fun markArmed() {
        armed = true
    }

    /** True once a guest-less close has decided to upload; read by the disconnect mixin. */
    fun isArmed(): Boolean = armed

    /** Publish the built pack for the screen to upload. */
    fun arm(pending: Pending) {
        this.pending = pending
    }

    /** The built pack, or null until the hook has produced it. */
    fun pending(): Pending? = pending

    /** Drop all state once the upload completes (or the player bails out). */
    fun clear() {
        armed = false
        pending = null
    }
}
