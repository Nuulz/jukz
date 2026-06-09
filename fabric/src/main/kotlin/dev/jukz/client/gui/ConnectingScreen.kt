package dev.jukz.client.gui

import net.minecraft.text.Text

/** Shown while establishing the transport to a host. [natPath] is the chosen traversal tier. */
class ConnectingScreen(natPath: String) : JukzStatusScreen(
    Text.literal("jukz"),
    Text.literal("Connecting to host (NAT: $natPath)…"),
)
