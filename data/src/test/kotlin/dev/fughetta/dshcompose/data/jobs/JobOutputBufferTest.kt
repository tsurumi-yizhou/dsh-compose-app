package dev.fughetta.dshcompose.data.jobs

import org.junit.Assert.*
import org.junit.Test

class JobOutputBufferTest {
    @Test
    fun streamingTerminalOutputStaysBoundedAcrossAnsiCarriageReturnsGapsAndUnicode() {
        val buffer = JobOutputBuffer()
        buffer.add("old\rprogress\r")
        buffer.add("\n\u001b[")
        buffer.add("31mred\u001b[0m\n")
        buffer.add("\u001b]0;" + "x".repeat(100000))
        buffer.add("\u001b")
        buffer.add("\\tail\n")
        assertEquals(listOf("progress", "red", "tail"), buffer.lines())
        val saved = buffer.lines()
        for (i in 0 until 12000) buffer.add("line$i\n")
        assertEquals(listOf("progress", "red", "tail"), saved)
        assertEquals(1000, buffer.lines().size)
        assertEquals("line11000", buffer.lines().first())
        assertEquals("line11999", buffer.lines().last())
        assertEquals(11003L, buffer.firstLine)
        assertTrue(buffer.trimmed)
        buffer.add("a".repeat(3999) + "😀" + "z".repeat(200000))
        assertEquals(1000, buffer.lines().size)
        assertEquals(3999, buffer.lines().last().length)
        assertFalse(buffer.lines().last().last().isSurrogate())
        buffer.markGap()
        buffer.add("new\n")
        assertEquals("new", buffer.lines().last())
        val progress = JobOutputBuffer()
        progress.add("working\r")
        assertEquals("working", progress.lines().single())
        progress.add("done")
        assertEquals("done", progress.lines().single())
        progress.add("😀".take(1))
        assertEquals("done", progress.lines().single())
        progress.add("😀".drop(1))
        assertEquals("done😀", progress.lines().single())
    }
}
