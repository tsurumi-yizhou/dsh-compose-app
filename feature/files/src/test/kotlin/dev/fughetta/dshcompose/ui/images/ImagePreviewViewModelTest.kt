package dev.fughetta.dshcompose.ui.images

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fughetta.dshcompose.domain.attachments.*
import dev.fughetta.dshcompose.domain.messages.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImagePreviewViewModelTest {
    private class Operations : AttachmentsRepository {
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

        var read: suspend (AttachmentReference) -> ByteArray = { error("unused") }
        var closes = 0

        override suspend fun readImage(sessionId: String, reference: AttachmentReference) =
            read(reference)

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

        override fun close() {
            closes++
        }
    }

    private class Repository(initial: AttachmentsRepository) {
        val connection = MutableStateFlow<AttachmentsRepository?>(initial)
    }

    private fun image(name: String) =
        ChatAttachment("image", name, name, "image/png", null, AttachmentReference())

    private fun create(store: ViewModelStore, repository: Repository) =
        ViewModelProvider(
            store,
            viewModelFactory { initializer { ImagePreviewViewModel(repository.connection) } },
        )[ImagePreviewViewModel::class.java]

    @Test
    fun supersededReadsAndRetiredConnectionsCannotReplaceTheCurrentPreview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val first = Operations()
        val repository = Repository(first)
        val oldImage = image("old")
        val latest = image("latest")
        val finish = CompletableDeferred<Unit>()
        first.read = { reference ->
            if (reference === oldImage.reference) withContext(NonCancellable) { finish.await() }
            if (reference === oldImage.reference) byteArrayOf(1) else byteArrayOf(2)
        }
        try {
            val model = create(store, repository)
            model.open("session", oldImage)
            runCurrent()
            model.open("session", latest)
            runCurrent()
            finish.complete(Unit)
            runCurrent()
            assertEquals("latest", model.uiState.value!!.name)
            assertArrayEquals(byteArrayOf(2), model.uiState.value!!.bytes)
            val second = Operations().apply { read = { byteArrayOf(3) } }
            repository.connection.value = second
            runCurrent()
            assertNull(model.uiState.value)
            model.open("other-session", image("other"))
            runCurrent()
            assertArrayEquals(byteArrayOf(3), model.uiState.value!!.bytes)
            store.clear()
            assertNull(model.uiState.value)
            assertEquals(0, first.closes)
            assertEquals(0, second.closes)
        } finally {
            finish.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun errorsRemainLocalAndClosingOrCancellingAReadReleasesThePreview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val operations =
            Operations().apply { read = { throw IllegalStateException("unavailable") } }
        val repository = Repository(operations)
        try {
            val model = create(store, repository)
            model.open("session", image("failed"))
            runCurrent()
            assertEquals("unavailable", model.uiState.value!!.error)
            assertNull(model.uiState.value!!.bytes)
            model.dismiss()
            assertNull(model.uiState.value)
            operations.read = { throw CancellationException("retired") }
            model.open("session", image("cancelled"))
            runCurrent()
            assertNull(model.uiState.value)
            repository.connection.value = null
            runCurrent()
            model.open("session", image("disconnected"))
            assertNull(model.uiState.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
