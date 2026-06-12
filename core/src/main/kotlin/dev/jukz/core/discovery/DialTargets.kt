package dev.jukz.core.discovery

import dev.jukz.core.transport.DialTarget

/**
 * The guest's connect ladder for this record: every advertised endpoint as a direct target (tried
 * in order), then — only if the host offered one — the relay session as the fallback. Keeping the
 * ordering here (pure, tested) lets [dev.jukz.core.join.JoinController] iterate targets without
 * knowing how each is reached.
 */
fun WorldRecord.dialTargets(): List<DialTarget> =
    endpoints.map { DialTarget.Direct(it) } + (relay?.let { listOf(DialTarget.ViaRelay(it.sessionId)) } ?: emptyList())
