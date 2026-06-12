package dev.jukz.core.transport

import dev.jukz.core.model.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DirectChannelDialerTest {

    private val fakeChannel = object : JukzChannel {
        override fun inputStream() = throw UnsupportedOperationException()
        override fun outputStream() = throw UnsupportedOperationException()
        override fun close() {}
    }

    private fun transportTo(channel: JukzChannel, assertHost: String? = null) = object : Transport {
        override suspend fun connect(endpoint: Endpoint): JukzChannel {
            if (assertHost != null) assertSame(assertHost, endpoint.host)
            return channel
        }
    }

    @Test
    fun `dials a direct target through the transport`() = runBlocking {
        val dialer = DirectChannelDialer(transportTo(fakeChannel, assertHost = "9.9.9.9"))
        assertSame(fakeChannel, dialer.dial(DialTarget.Direct(Endpoint("9.9.9.9", 25565))))
    }

    @Test
    fun `refuses a relay target (direct-only dialer)`() {
        val dialer = DirectChannelDialer(transportTo(fakeChannel))
        assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { dialer.dial(DialTarget.ViaRelay("cap-x")) }
        }
    }
}
