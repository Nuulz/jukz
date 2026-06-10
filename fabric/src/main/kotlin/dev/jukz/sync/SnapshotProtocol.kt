package dev.jukz.sync

/**
 * The tiny wire contract shared by the host's [SnapshotServer] and the guest's [JGitWorldSync.pullLatest]
 * for the one-shot world handoff (F4-A): a GET to [PATH] with a [PARAM_TOKEN] query param returns the
 * JGit pack bytes, and the HEAD commit id rides back in the [HEADER_HEAD] response header so the guest
 * knows which commit to reset to (a bare pack carries objects, not refs).
 */
internal object SnapshotProtocol {
    const val PATH = "/snapshot"
    const val PARAM_TOKEN = "token"
    const val HEADER_HEAD = "X-Jukz-Head"
}
