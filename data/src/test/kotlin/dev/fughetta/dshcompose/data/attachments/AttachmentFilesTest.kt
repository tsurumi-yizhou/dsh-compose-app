package dev.fughetta.dshcompose.data.attachments

import dev.fughetta.dshcompose.data.messages.EngineAttachmentsRepository
import dev.fughetta.dshcompose.domain.attachments.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Source(private val input: InputStream) {
        var closed = false

        fun metadata(source: AttachmentSource) =
            AttachmentSourceMetadata("/provider/notes.txt", "text/plain")

        fun open(source: AttachmentSource) =
            object : InputStream() {
                override fun read(): Int = input.read()

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    input.read(buffer, offset, length)

                override fun close() {
                    closed = true
                    input.close()
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationDuringResultHandoffReleasesTheUnclaimedPrivateCopy() = runTest {
        val queued = ArrayDeque<Runnable>()
        val dispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: kotlin.coroutines.CoroutineContext,
                    block: Runnable,
                ) {
                    queued.addLast(block)
                }
            }
        val directory = temporary.newFolder("handoff")
        val repository =
            EngineAttachmentsRepository(
                directory,
                { AttachmentSourceMetadata("image.gif", "image/gif") },
                { ByteArrayInputStream(byteArrayOf()) },
                dispatcher,
            )
        val task = launch {
            repository.importImage("GIF89a".toByteArray(), "image.gif", "image/gif")
            fail("The cancelled caller must not receive the copy")
        }
        runCurrent()
        // Finish writing while the caller is still waiting for its dispatched result.
        queued.removeFirst().run()
        task.cancel()
        runCurrent()
        while (queued.isNotEmpty()) {
            queued.removeFirst().run()
            runCurrent()
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(directory.listFiles()!!.isEmpty())
        repository.close()
    }

    @Test
    fun importedCopySurvivesProviderClosureAndRepositoryOwnsCleanup() = runTest {
        val directory = File(temporary.root, "spool")
        val source = Source(ByteArrayInputStream("notes".toByteArray()))
        val repository =
            EngineAttachmentsRepository(
                directory,
                source::metadata,
                source::open,
                StandardTestDispatcher(testScheduler),
            )
        val result = repository.importFile(AttachmentSource("content://test/file"), 10)
        assertTrue(source.closed)
        assertEquals("notes.txt", result.name)
        assertEquals("notes", File(result.path).readText())
        assertEquals(5L, result.bytes)
        val retained = repository.importImage("GIF89a".toByteArray(), "image.gif", "image/gif")
        repository.release(listOf(result))
        assertFalse(File(result.path).exists())
        assertTrue(File(retained.path).exists())
        repository.close()
        assertFalse(directory.exists())
    }

    @Test
    fun streamLimitAndCancellationReleaseProviderAndPartialSpool() = runTest {
        val directory = File(temporary.root, "spool")
        val source = Source(ByteArrayInputStream(ByteArray(20)))
        val repository =
            EngineAttachmentsRepository(
                directory,
                source::metadata,
                source::open,
                StandardTestDispatcher(testScheduler),
            )
        try {
            repository.importFile(AttachmentSource("content://test/file"), 10)
            fail("Import must stop at the remaining attachment budget")
        } catch (error: AttachmentImportException) {
            assertEquals("too_large", error.code)
        }
        assertTrue(source.closed)
        assertTrue(directory.listFiles().orEmpty().isEmpty())

        val job = launch {
            val cancellingSource =
                Source(
                    object : InputStream() {
                            override fun read(): Int {
                                currentJob.cancel()
                                return 1
                            }

                            lateinit var currentJob: Job

                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                currentJob.cancel()
                                buffer[offset] = 1
                                return 1
                            }
                        }
                        .apply { currentJob = currentCoroutineContext().job },
                )
            val cancelledRepository =
                EngineAttachmentsRepository(
                    directory,
                    cancellingSource::metadata,
                    cancellingSource::open,
                    StandardTestDispatcher(testScheduler),
                )
            try {
                cancelledRepository.importFile(AttachmentSource("content://test/file"), 10)
                fail("Cancellation must propagate")
            } finally {
                assertTrue(cancellingSource.closed)
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        repository.close()
    }

    @Test
    fun imageSignatureDeterminesTheTypeOfTheOwnedCopy() = runTest {
        val directory = File(temporary.root, "spool")
        val source = Source(ByteArrayInputStream(byteArrayOf()))
        val repository =
            EngineAttachmentsRepository(
                directory,
                source::metadata,
                source::open,
                StandardTestDispatcher(testScheduler),
            )
        val result = repository.importImage("GIF89a".toByteArray(), "animation.gif", "image/png")
        assertEquals("image/gif", result.mediaType)
        assertTrue(result.isImage)
        assertEquals("GIF89a", File(result.path).readText())
        repository.close()
    }
}
