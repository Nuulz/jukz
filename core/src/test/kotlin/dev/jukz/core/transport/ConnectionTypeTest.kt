package dev.jukz.core.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class ConnectionTypeTest {

    @Test
    fun `writes and reads back the discriminator for every type`() {
        for (type in ConnectionType.entries) {
            val sink = ByteArrayOutputStream()
            type.writeTo(outChannel(sink))
            val read = ConnectionType.readFrom(inChannel(sink.toByteArray()))
            assertEquals(type, read)
        }
    }

    @Test
    fun `rejects an unknown discriminator byte`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionType.readFrom(inChannel(byteArrayOf(0x09)))
        }
    }

    private fun outChannel(out: OutputStream): JukzChannel = object : JukzChannel {
        override fun inputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun outputStream(): OutputStream = out
        override fun close() {}
    }

    private fun inChannel(bytes: ByteArray): JukzChannel = object : JukzChannel {
        override fun inputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun outputStream(): OutputStream = ByteArrayOutputStream()
        override fun close() {}
    }
}
