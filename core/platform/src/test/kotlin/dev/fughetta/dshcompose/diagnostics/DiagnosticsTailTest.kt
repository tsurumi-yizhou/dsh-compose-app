package dev.fughetta.dshcompose.diagnostics

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsTailTest {
    @get:Rule val dir = TemporaryFolder()

    @Test
    fun missingFileIsEmpty() {
        assertEquals(emptyList<String>(), Diagnostics.tail(File(dir.root, "nope.log"), 1024))
    }

    @Test
    fun smallFileComesBackWhole() {
        val file = dir.newFile("dsh.log").apply { writeText("one\ntwo\nthree\n") }
        assertEquals(listOf("one", "two", "three"), Diagnostics.tail(file, 1024))
    }

    @Test
    fun cutFirstLineIsDropped() {
        val file = dir.newFile("dsh.log").apply { writeText("first line\nsecond\nthird\n") }
        // The last 12 bytes start inside "second"; 14 start on the newline before it.
        assertEquals(listOf("third"), Diagnostics.tail(file, 12))
        assertEquals(listOf("second", "third"), Diagnostics.tail(file, 14))
    }

    @Test
    fun multiByteTextSurvives() {
        val file = dir.newFile("dsh.log").apply { writeText("[dsh-app] 已启动\n引擎就绪\n") }
        assertEquals(listOf("[dsh-app] 已启动", "引擎就绪"), Diagnostics.tail(file, 4096))
    }
}
