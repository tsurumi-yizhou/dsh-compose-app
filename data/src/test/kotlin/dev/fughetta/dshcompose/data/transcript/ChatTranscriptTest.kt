package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.chat.ToolState
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactKind
import dev.fughetta.dshcompose.domain.sessions.TranscriptArtifactOrigin
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatTranscriptTest {
    private fun event(seq: Long, type: String, data: String) =
        SessionUpdate.Record(SessionRecord(seq, seq * 1000, type, parseObject(data)))

    private fun assistant(frame: String) = SessionUpdate.Assistant(parseObject(frame))

    private fun snapshot(records: List<SessionRecord> = emptyList(), stream: JsonObject? = null) =
        SessionUpdate.Snapshot(
            ConnectionGeneration(1, "client", "/root"),
            records.lastOrNull()?.seq ?: -1,
            records,
            false,
            JsonObject(emptyMap()),
            JsonObject(emptyMap()),
            stream,
            false,
        )

    @Test
    fun turnMetricsAndDeliverablesRemainOwnedByTheirCompletedTurn() {
        val transcript = ChatTranscript()
        val firstTurn =
            listOf(
                event(1, "turn/start", """{"turn":1}"""),
                event(2, "step/start", """{"step":1}"""),
                event(
                    3,
                    "assistant/message",
                    """{"step":1,"message":{"source":{"provider":"provider","model":"model"},"content":[{"type":"text","text":"Updating report"}]},"usage":{"inputTokens":10,"cacheReadTokens":20,"cacheWriteTokens":5,"outputTokens":8,"reasoningTokens":3},"stream":[{"type":"chunk","time":2200,"chunk":{"type":"text-delta"}}]}""",
                ),
                event(4, "tool/call", """{"callId":"edit1","name":"edit","arguments":"{}"}"""),
                event(
                    5,
                    "tool/result",
                    """{"message":{"toolCallId":"edit1","content":[]},"meta":{"path":"/work/report.csv","diffs":[{"oldText":"old","newText":"new"}]}}""",
                ),
                event(
                    6,
                    "deliverables/presented",
                    """{"turn":1,"callId":"deliver1","files":[{"path":"/work/report.csv","description":"Report"}]}""",
                ),
                event(7, "turn/end", """{"reason":{"kind":"done"}}"""),
            )
        transcript.apply(snapshot(firstTurn.map { it.event }))
        val published = transcript.presentation.turns
        val first = published.trace("1")!!
        assertTrue(first.completed)
        assertEquals(6000L, first.duration)
        assertEquals(43L, first.usage.total)
        assertEquals(3L, first.usage.reasoning)
        assertEquals(1, first.usage.requests)
        val model = first.spans.first()
        assertEquals(200L, model.ttft)
        assertEquals(10.0, model.tokensPerSecond!!, 0.0)
        assertEquals("model", model.model)
        val artifact = first.artifacts.single()
        assertEquals(TranscriptArtifactKind.Spreadsheet, artifact.kind)
        assertEquals(TranscriptArtifactOrigin.Delivered, artifact.origin)
        assertEquals("Report", artifact.description)
        val change = first.changes.single()
        assertEquals(artifact.path, change.path)
        assertEquals("old", change.blocks.single().old)
        assertEquals("new", change.blocks.single().new)

        listOf(
                event(8, "turn/start", """{"turn":2}"""),
                event(9, "step/start", """{"step":1}"""),
                event(10, "tool/call", """{"callId":"write2","name":"write","arguments":"{}"}"""),
                event(
                    11,
                    "tool/result",
                    """{"message":{"toolCallId":"write2","isError":true,"content":[]},"meta":{"path":"/work/failed.csv","operation":"create"}}""",
                ),
                event(12, "turn/end", """{"reason":{"kind":"aborted"}}"""),
            )
            .forEach(transcript::apply)
        val current = transcript.presentation.turns
        assertSame(first, current.trace("1"))
        assertNull(published.trace("2"))
        val second = current.trace("2")!!
        assertTrue(second.spans.first().cutShort)
        assertTrue(second.spans.last().failed)
        assertTrue(second.artifacts.isEmpty())
        assertTrue(second.changes.isEmpty())
        assertEquals(listOf(artifact), current.artifacts)
    }

    @Test
    fun modelConfirmationSurvivesOlderHistoryAndResyncUsesTheHostsNextSelection() {
        val transcript = ChatTranscript()
        transcript.apply(
            snapshot()
                .copy(
                    projections =
                        parseObject(
                            """{
            "values":{"modelSelection":{"next":{"provider":"p","model":"current","reasoningEffort":"high"},"lastUsed":{"provider":"p","model":"previous"}}}
        }""",
                        ),
                ),
        )
        assertEquals("current", transcript.model!!.model)
        transcript.confirmModel(ModelSelection("p", "confirmed", "off"))
        transcript.prependHistory(
            listOf(event(0, "model/selection", """{"provider":"p","model":"historical"}""").event),
            true,
        )
        assertEquals("confirmed", transcript.model!!.model)
        transcript.apply(
            snapshot(listOf(event(100, "turn/end", "{}").event))
                .copy(
                    projections =
                        parseObject(
                            """{
            "values":{"modelSelection":{"next":{"provider":"p","model":"host-next","reasoningEffort":"max"}}}
        }""",
                        ),
                ),
        )
        assertEquals("host-next", transcript.model!!.model)
        transcript.apply(
            event(
                101,
                "model/selection",
                """{"provider":"p","model":"changed-elsewhere","reasoningEffort":"low"}""",
            ),
        )
        assertEquals(ModelSelection("p", "changed-elsewhere", "low"), transcript.model)
    }

    @Test
    fun agentPresetComesFromProjectionsAndConfirmedSelections() {
        val transcript = ChatTranscript()
        assertNull(transcript.agentPreset)
        transcript.apply(
            snapshot().copy(projections = parseObject("""{"values":{"agentPreset":"ptc"}}""")),
        )
        assertEquals("ptc", transcript.agentPreset)
        transcript.confirmAgentPreset("minimal")
        assertEquals("minimal", transcript.agentPreset)
    }

    @Test
    fun longHistoryKeepsUnchangedRowsAndPublishedSnapshotsWhileStreamingAndPaging() {
        val transcript = ChatTranscript()
        var seq = 100_000L
        var oldestTool: ChatItem? = null
        repeat(20_000) { turn ->
            transcript.apply(
                event(
                    seq++,
                    "user/message",
                    """{"source":{"kind":"user"},"content":[{"type":"text","text":"Question $turn"}]}""",
                ),
            )
            transcript.apply(
                event(
                    seq++,
                    "tool/call",
                    """{"callId":"call$turn","name":"readFile","arguments":"{}"}""",
                ),
            )
            transcript.apply(
                event(
                    seq++,
                    "tool/result",
                    """{"message":{"toolCallId":"call$turn","content":[{"type":"text","text":"Result $turn"}]}}""",
                ),
            )
            transcript.apply(event(seq++, "turn/end", "{}"))
            val rows = transcript.items
            assertEquals((turn + 1) * 2, rows.size)
            if (oldestTool == null) oldestTool = rows[1] else assertSame(oldestTool, rows[1])
        }
        transcript.apply(
            event(seq++, "tool/call", """{"callId":"late","name":"bash","arguments":"{}"}"""),
        )
        transcript.pendingApprovals(setOf("late"))
        val before = transcript.items
        assertEquals(ToolState.AwaitingApproval, before.last().tool!!.state)
        transcript.apply(
            assistant(
                """{"type":"start","attemptId":"live","turn":20001,"step":1,"startedAfterSeq":${seq - 1}}""",
            ),
        )
        repeat(1000) { index ->
            transcript.apply(
                assistant(
                    """{"type":"chunk","attemptId":"live","index":$index,"chunk":{"type":"text-delta","index":0,"text":"x"}}""",
                ),
            )
            if (index % 100 == 0) {
                val rows = transcript.items
                assertSame(before.first(), rows.first())
                assertSame(oldestTool, rows[1])
                assertEquals(index + 1, rows.last().text.length)
            }
        }
        transcript.apply(
            event(
                seq++,
                "tool/result",
                """{"message":{"toolCallId":"late","content":[{"type":"text","text":"Done"}]}}""",
            ),
        )
        transcript.pendingApprovals(emptySet())
        val after = transcript.items
        assertEquals(ToolState.AwaitingApproval, before.last().tool!!.state)
        assertEquals(ToolState.Succeeded, after[40_000].tool!!.state)
        for (index in 0 until 40_000) assertSame(before[index], after[index])
        val older =
            (0L until 50).map {
                event(
                        it,
                        "user/message",
                        """{"source":{"kind":"user"},"content":[{"type":"text","text":"Older $it"}]}""",
                    )
                    .event
            }
        transcript.prependHistory(older, false)
        assertEquals("x".repeat(1000), transcript.items.last().text)
        transcript.apply(
            event(
                seq++,
                "assistant/message",
                """{"turn":20001,"step":1,"message":{"content":[{"type":"text","text":"${"x".repeat(1000)}"}]}}""",
            ),
        )
        val committed = transcript.items
        assertEquals(40_052, committed.size)
        assertEquals("Older 0", committed.first().text)
        assertEquals(after.last().key, committed.last().key)
        assertFalse(committed.last().streaming)
        assertEquals(40_001, before.size)
        assertFalse(transcript.hasMoreHistory)
    }

    @Test
    fun streamingDraftCommitsWithoutDuplicatingItsRowOrReasoning() {
        val transcript = ChatTranscript()
        transcript.apply(snapshot())
        transcript.apply(
            assistant(
                """{"type":"start","attemptId":"a1","turn":1,"step":1,"startedAfterSeq":-1}""",
            ),
        )
        transcript.apply(
            assistant(
                """{"type":"chunk","attemptId":"a1","index":0,"chunk":{"type":"reasoning-delta","index":0,"text":"Consider"}}""",
            ),
        )
        transcript.apply(
            assistant(
                """{"type":"chunk","attemptId":"a1","index":1,"chunk":{"type":"text-delta","index":1,"text":"Hello"}}""",
            ),
        )
        val draft = transcript.items.single()
        assertEquals("Hello", draft.text)
        assertEquals("Consider", draft.reasoning)
        assertTrue(draft.streaming)
        transcript.apply(
            event(
                0,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"content":[{"type":"reasoning","text":"Consider"},{"type":"text","text":"Hello!"}]}}""",
            ),
        )
        transcript.apply(
            assistant("""{"type":"end","attemptId":"a1","outcome":{"kind":"committed","seq":0}}"""),
        )
        val committed = transcript.items.single()
        assertEquals(draft.key, committed.key)
        assertEquals("Hello!", committed.text)
        assertFalse(committed.streaming)
    }

    @Test
    fun reconnectSnapshotRestoresCompactDraftThenAcceptsOnlyFreshChunks() {
        val transcript = ChatTranscript()
        val active =
            parseObject(
                """{"revision":4,"activeAttempt":{"attemptId":"a1","turn":2,"step":3,"startedAfterSeq":-1,"nextIndex":4,"stream":[{"type":"reasoning-chunks","index":0,"texts":["Think"," more"]},{"type":"text-chunks","index":1,"texts":["Hel","lo"]}]}}""",
            )
        transcript.apply(snapshot(stream = active))
        transcript.apply(
            assistant(
                """{"type":"chunk","attemptId":"a1","index":3,"chunk":{"type":"text-delta","index":1,"text":"duplicate"}}""",
            ),
        )
        transcript.apply(
            assistant(
                """{"type":"chunk","attemptId":"a1","index":4,"chunk":{"type":"text-delta","index":1,"text":"!"}}""",
            ),
        )
        assertEquals("Hello!", transcript.items.single().text)
        assertEquals("Think more", transcript.items.single().reasoning)
        transcript.apply(SessionUpdate.Reset(ConnectionGeneration(2, "new-client", "/root")))
        assertTrue(transcript.items.isEmpty())
    }

    @Test
    fun toolCallAndLateResultShareOneRowAndRespectTheRecordedApprovalOutcome() {
        val transcript = ChatTranscript()
        transcript.apply(
            event(
                0,
                "assistant/message",
                """{"turn":1,"step":1,"message":{"content":[{"type":"tool-call","id":"call1","name":"bash","arguments":"{}"}]}}""",
            ),
        )
        transcript.apply(
            event(1, "tool/call", """{"callId":"call1","name":"bash","arguments":"{}"}"""),
        )
        transcript.apply(event(2, "approval/asked", """{"id":"approval1","callId":"call1"}"""))
        assertEquals(ToolState.AwaitingApproval, transcript.items.single().tool?.state)
        transcript.apply(
            event(3, "approval/decided", """{"id":"approval1","outcome":"rejected"}"""),
        )
        transcript.apply(event(4, "turn/end", """{"reason":{"kind":"aborted"}}"""))
        transcript.apply(
            event(
                5,
                "tool/result",
                """{"message":{"toolCallId":"call1","isError":true,"content":[{"type":"text","text":"Permission denied"}]}}""",
            ),
        )
        val tool = transcript.items.single { it.role == ChatRole.Tool }
        assertEquals("tool:call1", tool.key)
        assertEquals("Permission denied", tool.text)
        assertEquals(ToolState.Denied, tool.tool?.state)
    }

    @Test
    fun snapshotsKeepAttachmentsAndHumanPromptsWhileHidingInjectedContext() {
        val records =
            listOf(
                    event(
                        0,
                        "user/message",
                        """{"source":{"kind":"runtime-context"},"content":[{"type":"text","text":"private runtime instructions"}]}""",
                    ),
                    event(
                        1,
                        "user/message",
                        """{"source":{"kind":"user","rpcId":"prompt1"},"content":[{"type":"image","attachment":{"attachmentId":"img1","mediaType":"image/png","name":"image.png"}}]}""",
                    ),
                )
                .map { it.event }
        val transcript = ChatTranscript()
        transcript.apply(snapshot(records))
        transcript.apply(snapshot(records))
        val item = transcript.items.single()
        assertEquals("prompt1", item.requestId)
        assertEquals("img1", item.attachments.single().attachmentId)
        assertEquals("image/png", item.attachments.single().mediaType)
        assertFalse(item.text.contains("instructions"))
    }
}
