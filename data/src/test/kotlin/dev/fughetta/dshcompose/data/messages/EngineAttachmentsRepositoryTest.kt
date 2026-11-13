package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.FileReceipt
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineAttachmentsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Source : AutoCloseable {
        val uploads = mutableListOf<String>()
        val submissions = mutableListOf<JsonObject>()
        val started = Channel<Unit>(Channel.UNLIMITED)
        val cancelled = Channel<Unit>(Channel.UNLIMITED)
        var blocked: CompletableDeferred<FileReceipt>? = null
        var failure: Exception? = null
        var read: suspend () -> ByteArray = { error("Unexpected image read") }
        private val transport =
            ProtocolHost(
                home = "/home",
                upload = { sessionId, _, _, onProgress ->
                    uploads.add(sessionId)
                    onProgress(2, 4)
                    started.send(Unit)
                    blocked?.let { gate ->
                        try {
                            gate.await()
                        } catch (error: CancellationException) {
                            cancelled.trySend(Unit)
                            throw error
                        }
                    } ?: FileReceipt("receipt-${uploads.size}", JsonObject(emptyMap()))
                },
            ) { endpoint, args ->
                when (endpoint) {
                    "session/attachment" ->
                        buildJsonObject { put("data", Base64.getEncoder().encodeToString(read())) }
                    "session/prompt" -> {
                        submissions += args.getValue("request").jsonObject
                        failure?.let { throw it }
                        JsonNull
                    }
                    else -> error("Unexpected request: $endpoint")
                }
            }
        val client
            get() = transport.client

        suspend fun reconnect() {
            client.mux.reconnect()
            client.mux.ready()
        }

        override fun close() = transport.close()
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Source()
        val repository = EngineAttachmentsRepository(source.client, scope)
        val pending = EngineMessagesRepository(scope)

        override fun close() {
            repository.close()
            pending.close()
            source.close()
            scope.cancel()
        }
    }

    private fun file(image: Boolean = false): ComposerFile {
        val file = temporary.newFile().apply { writeText("data") }
        return ComposerFile(
            file.name,
            file.name,
            if (image) "image/png" else "text/plain",
            (file).absolutePath,
            file.length(),
        )
    }

    private fun begin(
        fixture: Fixture,
        files: List<ComposerFile>,
        prepared: PreparedMessageAttachments,
    ) =
        fixture.pending.begin(
            "host",
            "/home",
            "session",
            "payload",
            "draft",
            null,
            files,
            fileReceiptIds = prepared.fileReceiptIds,
        )

    @Test
    fun imageReadsRetireWithTheirConnectionGeneration() = runBlocking {
        withTimeout(5000) {
            val source = Source().also { it.client.mux.ready() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineAttachmentsRepository(source.client, scope)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            source.read = {
                started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                byteArrayOf(1, 2, 3)
            }
            val reference =
                dev.fughetta.dshcompose.domain.attachments.AttachmentReference(
                    JsonObject(emptyMap()),
                )
            try {
                val read = async { repository.readImage("session", reference) }
                started.await()
                source.reconnect()
                finish.complete(Unit)
                try {
                    read.await()
                    fail("A retired read must not publish its bytes")
                } catch (_: CancellationException) {}
            } finally {
                finish.complete(Unit)
                repository.close()
                source.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun receiptsAreReusedOnlyWithinTheSameConversationAndGeneration() = runBlocking {
        withTimeout(5000) {
            Fixture()
                .also { it.source.client.mux.ready() }
                .use { fixture ->
                    val file = file()
                    val progress = mutableListOf<AttachmentUploadProgress>()
                    val first =
                        fixture.repository.prepare("session", listOf(file)) { progress.add(it) }
                    val repeated = fixture.repository.prepare("session", listOf(file))
                    assertEquals(first.fileReceiptIds, repeated.fileReceiptIds)
                    assertEquals(listOf("session"), fixture.source.uploads)
                    assertEquals(listOf(AttachmentUploadProgress(file.id, 2, 4)), progress)
                    val other = fixture.repository.prepare("other", listOf(file))
                    assertNotEquals(first.fileReceiptIds, other.fileReceiptIds)
                    fixture.source.reconnect()
                    val next = fixture.repository.prepare("session", listOf(file))
                    assertNotEquals(first.fileReceiptIds, next.fileReceiptIds)
                    assertEquals(listOf("session", "other", "session"), fixture.source.uploads)
                }
        }
    }

    @Test
    fun explicitRetryRebuildsImagesAndKeepsOriginalRequestAndReceiptIdentifiers() = runBlocking {
        withTimeout(5000) {
            Fixture()
                .also { it.source.client.mux.ready() }
                .use { fixture ->
                    val files = listOf(file(), file(image = true))
                    val prepared = fixture.repository.prepare("session", files)
                    val pending = begin(fixture, files, prepared)
                    val useCase = SubmitPendingPromptUseCase(fixture.pending)
                    fixture.source.failure = IOException("disconnected")
                    try {
                        useCase(pending, prepared.submission)
                        fail("The send outcome is unknown")
                    } catch (_: IOException) {}
                    assertEquals(
                        PendingPromptState.Uncertain,
                        fixture.pending.find(pending.owner, pending.requestId)?.state,
                    )
                    val retried = fixture.pending.retry(pending.owner, pending.requestId)
                    fixture.source.failure = null
                    File(files.single { it.isImage }.path).writeText("changed image")
                    assertTrue(useCase(retried, fixture.repository.prepareRetry(retried)))
                    assertEquals(listOf("session"), fixture.source.uploads)
                    assertEquals(
                        listOf(pending.requestId, pending.requestId),
                        fixture.source.submissions.map {
                            it.getValue("requestId").jsonPrimitive.content
                        },
                    )
                    assertTrue(
                        fixture.source.submissions.all {
                            it.getValue("content")
                                .jsonArray
                                .map { item -> item.jsonObject }
                                .filter { item -> item["type"] == JsonPrimitive("file") }
                                .map { item -> item.getValue("receiptId").jsonPrimitive.content } ==
                                prepared.fileReceiptIds
                        },
                    )
                    val images =
                        fixture.source.submissions.map { submission ->
                            submission
                                .getValue("content")
                                .jsonArray
                                .map { it.jsonObject }
                                .single { it["type"] == JsonPrimitive("image") }
                                .getValue("data")
                                .jsonPrimitive
                                .content
                        }
                    assertEquals(
                        listOf("data", "changed image").map {
                            Base64.getEncoder().encodeToString(it.toByteArray())
                        },
                        images,
                    )
                }
        }
    }

    @Test
    fun reconnectCancelsUploadsAndRetiresPreviouslyPreparedSubmissions() = runBlocking {
        withTimeout(5000) {
            Fixture()
                .also { it.source.client.mux.ready() }
                .use { fixture ->
                    val prepared = fixture.repository.prepare("session", emptyList())
                    val pending = begin(fixture, emptyList(), prepared)
                    fixture.source.blocked = CompletableDeferred()
                    val upload = async { fixture.repository.prepare("other", listOf(file())) }
                    fixture.source.started.receive()
                    fixture.source.reconnect()
                    fixture.source.cancelled.receive()
                    try {
                        upload.await()
                        fail("Old generation upload must retire")
                    } catch (_: CancellationException) {}
                    val outcome = prepared.submission(pending)
                    assertTrue(
                        outcome is PromptSubmissionResult.Failed &&
                            outcome.error is CancellationException,
                    )
                    assertTrue(fixture.source.submissions.isEmpty())
                    fixture.source.blocked = null
                    val next = fixture.repository.prepare("other", listOf(file()))
                    assertEquals(1, next.fileReceiptIds.size)
                    assertTrue(next.workspacePaths.isEmpty())
                    assertTrue(fixture.scope.isActive)
                }
        }
    }

    @Test
    fun deletingOneSessionCancelsItsUploadAndLeavesOtherSessionsAvailable() = runBlocking {
        withTimeout(5000) {
            Fixture()
                .also { it.source.client.mux.ready() }
                .use { fixture ->
                    fixture.source.blocked = CompletableDeferred()
                    val upload = async { fixture.repository.prepare("removed", listOf(file())) }
                    fixture.source.started.receive()
                    fixture.repository.forgetSession("removed")
                    fixture.source.cancelled.receive()
                    try {
                        upload.await()
                        fail("Deleted session upload must retire")
                    } catch (_: CancellationException) {}
                    fixture.source.blocked = null
                    assertEquals(
                        1,
                        fixture.repository.prepare("remaining", listOf(file())).fileReceiptIds.size,
                    )
                    assertTrue(fixture.scope.isActive)
                }
        }
    }
}
