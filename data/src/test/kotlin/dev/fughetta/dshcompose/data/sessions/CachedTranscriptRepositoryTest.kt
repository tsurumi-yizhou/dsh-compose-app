package dev.fughetta.dshcompose.data.sessions

import dev.fughetta.dshcompose.data.messages.EngineMessagesRepository
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CachedTranscriptRepositoryTest {
    private fun record(seq: Long, text: String) =
        SessionRecord(
            seq,
            seq,
            "user/message",
            buildJsonObject {
                putJsonObject("source") { put("kind", "user") }
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            },
        )

    private fun repository(
        scope: CoroutineScope,
        records: List<SessionRecord>,
    ): Pair<EngineTranscriptRepository, AutoCloseable> {
        val pending = EngineMessagesRepository(scope)
        val catalog = EngineSessionsRepository()
        val transcript =
            cachedTranscriptRepository(
                host = "host",
                sessionId = "session",
                home = "/home",
                records = records,
                parent = scope,
                pendingMessages = pending,
                catalog = catalog,
                clock = TranscriptClock(),
            )
        return transcript to
            AutoCloseable {
                transcript.close()
                pending.close()
                catalog.close()
            }
    }

    @Test
    fun aMirroredTranscriptPublishesTheSamePresentationTheLiveFoldWould() = runBlocking {
        withTimeout(15_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val (transcript, close) = repository(scope, listOf(record(1, "cached hello")))
            try {
                val publication =
                    withTimeout(10_000) { transcript.publications({ true }).first { !it.syncing } }
                assertEquals(
                    listOf("cached hello"),
                    publication.presentation.items
                        .filter { it.role == ChatRole.User }
                        .map { it.text },
                )
                assertEquals(0L, publication.generation?.number)
                assertFalse(publication.hasMoreHistory)
            } finally {
                close.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun anEmptyMirrorPublishesAnEmptyConversationInsteadOfFailing() = runBlocking {
        withTimeout(15_000) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val (transcript, close) = repository(scope, emptyList())
            try {
                val publication =
                    withTimeout(10_000) { transcript.publications({ true }).first { !it.syncing } }
                assertTrue(publication.presentation.items.isEmpty())
            } finally {
                close.close()
                scope.cancel()
            }
        }
    }
}
