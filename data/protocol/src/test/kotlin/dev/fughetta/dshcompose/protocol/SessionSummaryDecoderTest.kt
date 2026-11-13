package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import org.junit.Assert.*
import org.junit.Test

class SessionSummaryDecoderTest {
    @Test
    fun catalogAndTranscriptSnapshotsExposeTheSameTypedSessionMetadata() {
        val projections =
            parseObject(
                """{
            "values": {
                "title":"Worker",
                "sessionListMetadata":{"blank":true},
                "subagentCatalog":[{"id":"child","label":"Research","mode":"continuable","createdAt":30}],
                "subagent":{"label":"Worker identity","mode":"one-shot"},
                "subagentTiming":{"settledMs":40,"active":{"since":100,"through":130},"lastTurnCompleted":false}
            }
        }""",
            )
        val catalog =
            decodeSessionSummary(
                parseObject(
                    """{
            "sessionId":"worker","cwd":"/work","running":true,"blank":true,
            "agentAvailable":false,"parentSessionId":"parent","origin":"subagent","updatedAt":50,
            "projections":$projections
        }""",
                ),
            )
        val snapshot =
            SessionUpdate.Snapshot(
                ConnectionGeneration(1, "client", "/home"),
                -1,
                emptyList(),
                false,
                parseObject(
                    """{"cwd":"/work","parentSessionId":"parent","origin":"subagent","createdAt":50}""",
                ),
                projections,
                null,
                false,
            )
        assertEquals(catalog, sessionSummaryFromSnapshot("worker", snapshot, running = true))
        assertEquals("Worker", catalog.title)
        assertEquals("Research", catalog.subagents.catalog.single().label)
        assertEquals("continuable", catalog.subagents.catalog.single().mode)
        assertEquals("Worker identity", catalog.subagents.identity!!.label)
        assertEquals(70L, catalog.subagents.timing!!.elapsedMs)
        assertEquals(false, catalog.subagents.timing!!.lastTurnCompleted)
    }
}
