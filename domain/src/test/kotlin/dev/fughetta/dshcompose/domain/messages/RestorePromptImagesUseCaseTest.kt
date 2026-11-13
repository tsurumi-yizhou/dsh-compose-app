package dev.fughetta.dshcompose.domain.messages

import dev.fughetta.dshcompose.domain.attachments.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RestorePromptImagesUseCaseTest {
    private class Images : AttachmentsRepository {
        override suspend fun importImage(
            bytes: ByteArray,
            name: String?,
            mediaType: String?,
        ): dev.fughetta.dshcompose.domain.attachments.ComposerFile = error("unused")

        override suspend fun importFile(
            source: dev.fughetta.dshcompose.domain.attachments.AttachmentSource,
            availableBytes: Long,
        ): dev.fughetta.dshcompose.domain.attachments.ComposerFile = error("unused")

        override suspend fun release(
            files: List<dev.fughetta.dshcompose.domain.attachments.ComposerFile>,
        ) = error("unused")

        override fun close() = Unit

        val reads = mutableListOf<Pair<String, AttachmentReference>>()
        var answer: suspend () -> ByteArray = { byteArrayOf(1, 2, 3) }

        override suspend fun readImage(
            sessionId: String,
            reference: AttachmentReference,
        ): ByteArray {
            reads += sessionId to reference
            return answer()
        }

        override suspend fun prepare(
            sessionId: String,
            files: List<ComposerFile>,
            onProgress: suspend (AttachmentUploadProgress) -> Unit,
        ): PreparedMessageAttachments = error("unused")

        override suspend fun prepareRetry(pending: PendingPrompt): PendingPromptSubmission =
            error("unused")

        override fun forget(attachmentIds: Set<String>) {}

        override fun retain(attachmentIds: Set<String>) {}

        override fun forgetSession(sessionId: String) {}
    }

    private class Files {
        val live = mutableListOf<ComposerFile>()
        val released = mutableListOf<ComposerFile>()

        suspend fun importFile(
            source: AttachmentSource,
            availableBytes: Long,
        ): ComposerFile = error("unused")

        suspend fun importImage(
            bytes: ByteArray,
            name: String?,
            mediaType: String?,
        ): ComposerFile =
            ComposerFile(name!!, name, mediaType!!, (File(name)).absolutePath, bytes.size.toLong())
                .also { live += it }

        suspend fun release(files: List<ComposerFile>) {
            live.removeAll(files.toSet())
            released += files
        }

        fun close() {}
    }

    private fun image(name: String) =
        ChatAttachment("image", name, name, "image/png", 3, AttachmentReference())

    @Test
    fun successfulRestorationTransfersCopiesToTheCallerWithTheirNamesAndTypes() = runBlocking {
        val images = Images()
        val files = Files()
        val input = listOf(image("first"), image("second"))
        val restored =
            RestorePromptImagesUseCase(images, files::importImage, files::release)("session", input)
        assertEquals(input.map { "session" to it.reference }, images.reads)
        assertEquals(listOf("first", "second"), restored.map { it.name })
        assertTrue(restored.all { it.mediaType == "image/png" && it.bytes == 3L })
        assertEquals(restored, files.live)
        assertTrue(files.released.isEmpty())
        files.release(restored)
        assertTrue(files.live.isEmpty())
    }

    @Test
    fun failureOrCancellationAfterAnImportReleasesTheWholePartialRestoration() = runBlocking {
        for (failure in
            listOf(IllegalStateException("download failed"), CancellationException("retired"))) {
            val images = Images()
            val files = Files()
            images.answer = {
                if (images.reads.size == 2) throw failure
                byteArrayOf(1, 2, 3)
            }
            try {
                RestorePromptImagesUseCase(images, files::importImage, files::release)(
                    "session",
                    listOf(image("first"), image("second")),
                )
                fail("The restoration must report its failure")
            } catch (error: Exception) {
                assertSame(failure, error)
            }
            assertTrue(files.live.isEmpty())
            assertEquals(listOf("first"), files.released.map { it.name })
        }
    }
}
