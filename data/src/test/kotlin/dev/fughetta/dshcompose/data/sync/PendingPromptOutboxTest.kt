package dev.fughetta.dshcompose.data.sync

import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.messages.PendingPrompt
import dev.fughetta.dshcompose.domain.messages.PendingPromptState
import dev.fughetta.dshcompose.domain.messages.PromptMode
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PendingPromptOutboxTest {
    private val host = "host"
    private val temporary = mutableListOf<File>()

    @After
    fun cleanup() {
        temporary.forEach { it.delete() }
    }

    private fun prompt(
        requestId: String = "request-1",
        host: String = this.host,
        sessionId: String = "session",
        text: String = "hello",
        files: List<ComposerFile> = emptyList(),
        mode: PromptMode = PromptMode.Queue,
    ) =
        PendingPrompt(
            requestId = requestId,
            host = host,
            home = "/home",
            sessionId = sessionId,
            text = text,
            quote = "quoted",
            composerText = "hello",
            files = files.toPersistentList(),
            state = PendingPromptState.Uncertain,
            itemId = "item",
            attempt = 2,
            errorCode = "message/outcome-unknown",
            composerOwned = false,
            mode = mode,
            timeZone = "Asia/Taipei",
            fileReceiptIds = persistentListOf("receipt-a"),
        )

    private fun attachment(): ComposerFile {
        val file = File.createTempFile("dsh-prompt-", ".txt").apply { writeText("payload") }
        temporary += file
        return ComposerFile("file-1", "note.txt", "text/plain", (file).absolutePath, file.length())
    }

    @Test
    fun aRetainedPromptComesBackWithItsFields() = runTest {
        val outbox = FakeOutbox()
        val queue = PendingPromptOutbox(outbox) { 1_000L }
        queue.retain(prompt(files = listOf(attachment()), mode = PromptMode.Steer))

        val restored = queue.outstanding(host).single()

        assertEquals("request-1", restored.requestId)
        assertEquals(host, restored.host)
        assertEquals("/home", restored.home)
        assertEquals("session", restored.sessionId)
        assertEquals("hello", restored.text)
        assertEquals("quoted", restored.quote)
        assertEquals(PendingPromptState.Uncertain, restored.state)
        assertEquals(PromptMode.Steer, restored.mode)
        assertEquals("Asia/Taipei", restored.timeZone)
        assertEquals(2L, restored.attempt)
        assertFalse(restored.composerOwned)
        assertEquals(listOf("receipt-a"), restored.fileReceiptIds)
        assertEquals(1, restored.files.size)
        assertEquals("note.txt", restored.files.single().name)
    }

    @Test
    fun aMissingAttachmentIsDroppedButTheTextStays() = runTest {
        val outbox = FakeOutbox()
        val queue = PendingPromptOutbox(outbox) { 1_000L }
        val attachment = attachment()
        queue.retain(prompt(files = listOf(attachment)))
        java.io.File(attachment.path).delete()

        val restored = queue.outstanding(host).single()

        assertEquals("hello", restored.text)
        assertTrue(restored.files.isEmpty())
    }

    @Test
    fun retainingTheSameRequestReplacesTheRow() = runTest {
        val outbox = FakeOutbox()
        val queue = PendingPromptOutbox(outbox) { 1_000L }
        queue.retain(prompt(text = "first"))
        queue.retain(prompt(text = "second"))

        assertEquals(listOf("second"), queue.outstanding(host).map { it.text })
        assertEquals(1, outbox.storage.size)
    }

    @Test
    fun settlingRemovesTheRowAndRejectedRowsAreNotOutstanding() = runTest {
        val outbox = FakeOutbox()
        val queue = PendingPromptOutbox(outbox) { 1_000L }
        queue.retain(prompt("request-1"))
        queue.retain(prompt("request-2"))

        queue.rejected(host, "request-1")
        queue.settle(host, "request-2")

        assertTrue(queue.outstanding(host).isEmpty())
    }

    @Test
    fun hostsDoNotSeeEachOthersPendingPrompts() = runTest {
        val outbox = FakeOutbox()
        val queue = PendingPromptOutbox(outbox) { 1_000L }
        queue.retain(prompt("a", host = host))
        queue.retain(prompt("b", host = "other"))

        assertEquals(listOf("a"), queue.outstanding(host).map { it.requestId })
        assertEquals(listOf("b"), queue.outstanding("other").map { it.requestId })
    }
}
