package dev.jukz.client.gui

import net.minecraft.text.Text

/** Brief working overlay while the world is being opened and published to discovery. */
class OpeningWorldScreen : JukzStatusScreen(
    Text.literal("Opening your world"),
    Text.literal("Sharing it with jukz…"),
    accentColor = ACCENT_CONNECT,
)
