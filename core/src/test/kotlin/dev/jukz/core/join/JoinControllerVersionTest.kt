package dev.jukz.core.join

import dev.jukz.core.discovery.InMemoryWorldRegistry
import dev.jukz.core.discovery.WorldRecord
import dev.jukz.core.model.ClaimToken
import dev.jukz.core.model.Endpoint
import dev.jukz.core.model.GameVersion
import dev.jukz.core.model.NodeId
import dev.jukz.core.model.WorldId
import dev.jukz.core.transport.ChannelDialer
import dev.jukz.core.util.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

class JoinControllerVersionTest {

    private val world = WorldId.random()
    private val token = ClaimToken(1, 1, NodeId(ByteArray(16) { 5 }))
    private val v1211 = GameVersion("1.21.1", 3955)
    private val v12111 = GameVersion("1.21.11", 4671)

    private fun joinAs(mine: GameVersion, host: GameVersion?): Pair<JoinResult, Boolean> = runBlocking {
        val registry = InMemoryWorldRegistry(SystemClock)
        registry.publishIfNewer(WorldRecord(world, token, listOf(Endpoint("127.0.0.1", 1)), heartbeatSeq = 0, game = host))
        val dialled = AtomicBoolean(false)
        val dialer = ChannelDialer { dialled.set(true); error("must not dial") }
        val handoff = object : GameHandoff { override fun connect(host: String, port: Int) {} }
        val result = JoinController(registry, dialer, handoff, SystemClock, JoinConfig(game = mine)).use { it.join(world) }
        result to dialled.get()
    }

    @Test
    fun `a host on a newer version is refused before dialling`() {
        val (result, dialled) = joinAs(v1211, v12111)
        assertEquals(JoinResult.WrongVersion(v12111), result)
        assertFalse(dialled)
    }

    @Test
    fun `a host on an older version is refused too`() {
        assertEquals(JoinResult.WrongVersion(v1211), joinAs(v12111, v1211).first)
    }

    @Test
    fun `an older mod's record counts as 1,21,1`() {
        assertEquals(JoinResult.WrongVersion(GameVersion.LEGACY), joinAs(v12111, null).first)
    }
}
