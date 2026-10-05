package dev.jukz.core.model

/**
 * The Minecraft version a world was last played on: [name] for people ("1.21.11") and [dataVersion],
 * the world-format number Minecraft writes into every save, for comparing. A world can only be played
 * on its own data version or a newer one, and opening it on a newer one upgrades it for good.
 */
data class GameVersion(val name: String, val dataVersion: Int) {
    init {
        require(name.isNotBlank()) { "game version name must not be blank" }
        require(dataVersion > 0) { "data version must be positive" }
    }

    companion object {
        /**
         * What a record, backup or account entry without a version means: everything jukz wrote before it
         * tracked versions was Minecraft 1.21.1.
         */
        val LEGACY = GameVersion("1.21.1", 3955)
    }
}

/** What a client on [mine] may do with a world last played on [theirs]. */
enum class VersionFit {
    /** Same version: play, join, host as usual. */
    SAME,

    /** The world is older: it can be opened, but that upgrades it and older clients lose it. */
    UPGRADE,

    /** The world is newer than this game: it can't be opened or joined here. */
    TOO_NEW;

    companion object {
        fun of(mine: GameVersion, theirs: GameVersion?): VersionFit {
            val data = (theirs ?: GameVersion.LEGACY).dataVersion
            return when {
                data == mine.dataVersion -> SAME
                data < mine.dataVersion -> UPGRADE
                else -> TOO_NEW
            }
        }
    }
}
