package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalsTest {
    private fun goal(id: String = "goal", revision: Long = 2, phase: String = "active") =
        """{"id":"$id","revision":$revision,"objective":"Finish the task","phase":"$phase","maxGoalRounds":256}"""

    private fun record(seq: Long, type: String, data: String) =
        SessionRecord(seq, seq, type, parseObject(data))

    @Test
    fun projectedGoalsAndRoundCountersIgnoreOldHistoryAndKeepUnrelatedStreamUpdatesCheap() {
        val transcript = ChatTranscript()
        fun snapshot(
            cursor: Long,
            value: String,
            records: List<SessionRecord> = emptyList(),
            replaced: Boolean = false,
        ) =
            SessionUpdate.Snapshot(
                ConnectionGeneration(1, "client", "/work"),
                cursor,
                records,
                true,
                JsonObject(emptyMap()),
                parseObject("""{"asOfSeq":$cursor,"values":{"goal":$value}}"""),
                null,
                replaced,
            )
        transcript.apply(
            snapshot(
                100,
                """{"goal":${goal()},"roundsStarted":8,"createdAt":1,"updatedAt":2}""",
                listOf(
                    record(
                        99,
                        "goal/change",
                        """{"operation":"edit","goal":${goal(revision = 1)},"roundsStarted":1}""",
                    ),
                ),
            ),
        )
        val seeded = transcript.goal!!
        assertEquals(2L, seeded.goal.ref.revision)
        assertEquals(8L, seeded.roundsStarted)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    101,
                    "user/message",
                    """{"source":{"kind":"goal","goalId":"goal","round":9},"content":[]}""",
                ),
            ),
        )
        val round = transcript.goal!!
        assertEquals(9L, round.roundsStarted)
        assertEquals(8L, seeded.roundsStarted)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    102,
                    "user/message",
                    """{"source":{"kind":"goal","goalId":"other","round":90},"content":[]}""",
                ),
            ),
        )
        assertSame(round, transcript.goal)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    103,
                    "user/message",
                    """{"source":{"kind":"goal","goalId":"goal","round":2},"content":[]}""",
                ),
            ),
        )
        assertSame(round, transcript.goal)
        repeat(1000) { index ->
            transcript.apply(
                SessionUpdate.Record(
                    record(
                        104 + index.toLong(),
                        "tool/call",
                        """{"callId":"c$index","name":"read","arguments":"{}"}""",
                    ),
                ),
            )
        }
        assertSame(round, transcript.goal)
        transcript.prependHistory(
            (0L until 90).map {
                record(it, "goal/change", """{"operation":"edit","goal":${goal(revision = 1)}}""")
            },
            false,
        )
        assertSame(round, transcript.goal)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    1104,
                    "goal/change",
                    """{"operation":"pause","goal":${goal(revision = 3, phase = "paused")},"roundsStarted":9}""",
                ),
            ),
        )
        assertEquals(GoalPhase.Paused, transcript.goal!!.goal.phase)
        transcript.apply(
            SessionUpdate.Record(record(1105, "goal/change", """{"operation":"clear"}""")),
        )
        assertNull(transcript.goal)
        transcript.apply(snapshot(2000, "null"))
        assertNull(transcript.goal)
        transcript.apply(
            snapshot(
                10,
                """{"goal":${goal("replacement", 1)},"roundsStarted":0}""",
                replaced = true,
            ),
        )
        transcript.prependHistory(
            listOf(record(5, "goal/change", """{"operation":"clear"}""")),
            false,
        )
        assertEquals("replacement", transcript.goal!!.goal.ref.id)
    }

    @Test
    fun goalMutationsAddressTheirSessionAndExactRevisionWhileRemoteReadAvailabilityIsExplicit() =
        runBlocking {
            val calls = mutableListOf<String>()
            val base =
                HostConnection(
                    "http://localhost",
                    engine =
                        MockEngine { request ->
                            val body = parseObject((request.body as TextContent).text)
                            val method = body.requiredString("method")
                            calls.add(method)
                            val args = body["args"]!!.objectValue()
                            assertEquals("session", args.requiredString("agentId"))
                            val value =
                                when (method) {
                                    "goals/create" -> {
                                        assertEquals(
                                            parseObject(
                                                """{"objective":"Objective","maxGoalRounds":7}""",
                                            ),
                                            args["request"],
                                        )
                                        parseObject("""{"ref":{"id":"goal","revision":1}}""")
                                    }
                                    "goals/get" ->
                                        parseObject(
                                            """${goal(revision = 2).dropLast(1)},"roundsStarted":3,"activation":"disarmed"}""",
                                        )
                                    else -> {
                                        assertEquals(
                                            parseObject("""{"id":"goal","revision":2}"""),
                                            args["ref"],
                                        )
                                        if (method == "goals/edit")
                                            assertEquals(
                                                parseObject("""{"objective":"Revised"}"""),
                                                args["request"],
                                            )
                                        if (method == "goals/clear")
                                            parseObject("""{"id":"goal","revision":3}""")
                                        else
                                            parseObject(
                                                """${goal(revision = 3).dropLast(1)},"roundsStarted":3,"activation":"armed"}""",
                                            )
                                    }
                                }
                            respond(
                                buildJsonObject {
                                    put("id", body["id"]!!)

                                    put("result", value)
                                }
                                    .toString(),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )
            val client = ProtocolClient(base)
            try {
                assertEquals(GoalRef("goal", 1), client.createGoal("session", " Objective ", 7))
                val view = client.goal("session")!!
                assertEquals(GoalActivation.Disarmed, view.activation)
                assertEquals(
                    3L,
                    client.editGoal("session", view.ref, objective = " Revised ").ref.revision,
                )
                for (action in
                    listOf(GoalAction.Pause, GoalAction.Resume, GoalAction.Complete)) client
                    .changeGoal("session", view.ref, action)
                assertEquals(GoalRef("goal", 3), client.clearGoal("session", view.ref))
                val remote =
                    ProtocolClient(
                        object : HostTransport by base {
                            override val supportsGoalRead = false
                        },
                    )
                val count = calls.size
                try {
                    remote.goal("session")
                    fail("Expected unavailable read")
                } catch (error: HostRemoteException) {
                    assertEquals("GOAL_READ_UNSUPPORTED", error.code)
                }
                assertEquals(count, calls.size)
                remote.close()
                val edge =
                    goalActivationChange(
                        parseObject(
                            """{"type":"emit","event":"goal/activation-changed","args":[{"sessionId":"session","goal":{"id":"goal","revision":2,"activation":"armed"}}]}""",
                        ),
                    )!!
                assertEquals(view.ref, edge.ref)
                assertEquals(GoalActivation.Armed, edge.activation)
            } finally {
                client.close()
            }
        }
}
