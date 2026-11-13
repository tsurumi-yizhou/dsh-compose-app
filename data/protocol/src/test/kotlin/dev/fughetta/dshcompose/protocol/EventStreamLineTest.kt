package dev.fughetta.dshcompose.protocol

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EventStreamLineTest {
    @Test
    fun acceptsEventDelimitersAndFinalUtf8LineWhileKeepingTheByteLimit() = runBlocking {
        val channel = ByteReadChannel("data: 中文\r\ndata: two\rdata: last".toByteArray())
        assertEquals("data: 中文", channel.readEventLine())
        assertEquals("data: two", channel.readEventLine())
        assertEquals("data: last", channel.readEventLine())
        assertNull(channel.readEventLine())
        val long = ByteReadChannel(("x".repeat(1024 * 1024 + 1) + "\n").toByteArray())
        try {
            long.readEventLine()
            fail("oversized line must be rejected")
        } catch (_: io.ktor.utils.io.charsets.TooLongLineException) {}
    }
}
