package dev.fughetta.dshcompose.nativefiles

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SelectedFileReaderTest {
    @Test
    fun streamsOnlySelectedHandlesAndEnforcesActualBytesWithoutTrustingMetadata() {
        val budget = SelectedFileBudget(2, 8, 12)
        budget.metadata(listOf(null, null))
        val opened = mutableListOf<String>()
        var closes = 0
        val reader =
            SelectedFileReader(budget, setOf("a", "b")) { handle ->
                opened += handle
                object : ByteArrayInputStream(ByteArray(if (handle == "a") 5 else 8) { 7 }) {
                    override fun close() {
                        closes++
                        super.close()
                    }
                }
            }
        try {
            assertEquals(
                "cancelled",
                assertThrows(NativeFileException::class.java) { reader.read("unselected") }.code,
            )
            assertTrue(opened.isEmpty())
            assertEquals(5, reader.read("a")!!.size)
            assertNull(reader.read("a"))
            assertNull(reader.read("a"))
            assertEquals(1, closes)
            assertThrows(IllegalArgumentException::class.java) { reader.read("b") }
            assertEquals(listOf("a", "b"), opened)
        } finally {
            reader.close()
        }
        assertEquals(2, closes)
        assertEquals(
            "cancelled",
            assertThrows(NativeFileException::class.java) { reader.read("a") }.code,
        )
    }

    @Test
    fun closingASelectionInterruptsItsBlockedStreamAndDiscardsTheReadResult() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val reader =
            SelectedFileReader(SelectedFileBudget(1, 8, 8), setOf("file")) {
                object : InputStream() {
                    override fun read(): Int {
                        started.countDown()
                        check(closed.await(5, TimeUnit.SECONDS))
                        return -1
                    }

                    override fun close() {
                        closed.countDown()
                    }
                }
            }
        val read = CompletableFuture.supplyAsync {
            try {
                reader.read("file")
                "unexpected-success"
            } catch (error: NativeFileException) {
                error.code
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            reader.close()
            assertEquals("cancelled", read.get(5, TimeUnit.SECONDS))
        } finally {
            reader.close()
        }
    }
}
