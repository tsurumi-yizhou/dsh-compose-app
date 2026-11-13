package dev.fughetta.dshcompose.data.jobs

import dev.fughetta.dshcompose.domain.jobs.JobOutputExportException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalJobOutputFilesRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun closeScope() {
        writes.cancel()
    }

    @Test
    fun exportsUseTheCapturedLinesAndKeepABoundedShareCache() = runBlocking {
        val directory = temporary.newFolder("shares")
        val document = ByteArrayOutputStream()
        var closed = false
        var destination: String? = null
        val source =
            JobOutputFilesDataSource(directory) { uri ->
                destination = uri
                object : OutputStream() {
                    override fun write(value: Int) {
                        document.write(value)
                    }

                    override fun close() {
                        closed = true
                    }
                }
            }
        val repository = EngineJobsRepository(source, writes)
        val lines = listOf("First 😀", "", "Last")
        val shared = repository.share(lines)
        assertEquals("First 😀\n\nLast", java.io.File(shared).readText())
        repeat(10) { repository.share(listOf("output $it")) }
        assertEquals(8, directory.listFiles()!!.size)
        repository.save("content://documents/output", lines)
        assertEquals("content://documents/output", destination)
        assertEquals("First 😀\n\nLast", document.toString(Charsets.UTF_8))
        assertTrue(closed)
        assertEquals(8, directory.listFiles()!!.size)
    }

    @Test
    fun failedDocumentWritesCloseTheStreamAndReturnAnExportError() = runBlocking {
        var closed = false
        val repository =
            EngineJobsRepository(
                JobOutputFilesDataSource(temporary.newFolder()) {
                    object : OutputStream() {
                        override fun write(value: Int) {
                            throw IOException("provider failure")
                        }

                        override fun close() {
                            closed = true
                        }
                    }
                },
                writes,
            )
        try {
            repository.save("content://documents/output", listOf("text"))
            fail("The caller must receive the write failure")
        } catch (error: JobOutputExportException) {
            assertEquals("jobs/save-failed", error.code)
        }
        assertTrue(closed)
    }

    @Test
    fun selectedDocumentFinishesWritingAfterTheCallerLeaves() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val document = ByteArrayOutputStream()
        val repository =
            EngineJobsRepository(
                JobOutputFilesDataSource(temporary.newFolder()) {
                    object : OutputStream() {
                        override fun write(value: Int) {
                            started.complete(Unit)
                            release.await()
                            document.write(value)
                        }

                        override fun close() {
                            finished.complete(Unit)
                        }
                    }
                },
                writes,
            )
        try {
            val caller = launch {
                repository.save("content://documents/output", listOf("captured"))
            }
            started.await()
            caller.cancelAndJoin()
            release.countDown()
            finished.await()
            assertEquals("captured", document.toString(Charsets.UTF_8))
        } finally {
            release.countDown()
        }
    }
}
