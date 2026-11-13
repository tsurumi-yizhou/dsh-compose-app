package dev.fughetta.dshcompose.protocol

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SchedulesTest {
    @Test
    fun taskLifecycleUsesHostPaginationDestinationsAndRevisionWhileRetainingEngineRefusals() =
        runBlocking {
            val task =
                parseObject(
                    """{"id":"task","title":"Reminder","prompt":"Review","kind":"every","everySeconds":3600,"status":"active","target":{"kind":"new_once","sessionId":"reserved","create":{"cwd":"/project"}},"involvedSessionIds":[],"scheduledAt":"2026-11-08T10:00:00Z","updatedAt":"revision"}""",
                )
            val methods = mutableListOf<String>()
            val transport =
                HostConnection(
                    "http://localhost",
                    engine =
                        MockEngine { request ->
                            val body = parseObject((request.body as TextContent).text)
                            val method = body.requiredString("method")
                            methods.add(method)
                            val args = body["args"]!!.objectValue()
                            val input = args["request"] as? JsonObject
                            assertFalse(args.containsKey("agentId"))
                            val value =
                                when (method) {
                                    "schedule/create" -> {
                                        assertEquals(
                                            parseObject(
                                                """{"kind":"new_once","create":{"cwd":"/project"}}""",
                                            ),
                                            input!!["target"],
                                        )
                                        assertEquals("Reminder", input.string("title"))
                                        task
                                    }
                                    "schedule/list" -> {
                                        assertEquals(100L, input!!.long("limit"))
                                        assertEquals(100L, input.long("offset"))
                                        parseObject(
                                            """{"items":[$task],"shown":1,"total":101,"limit":100,"offset":100}""",
                                        )
                                    }
                                    "schedule/history" -> {
                                        assertEquals("cursor", input!!.string("before"))
                                        assertEquals(20L, input.long("limit"))
                                        parseObject(
                                            """{"id":"task","records":[{"messageId":"delivery","targetSessionId":"actual","scheduledAt":"2026-11-08T09:00:00Z","deliveredAt":"2026-11-08T09:00:02Z","prompt":"Review"}],"earlierRecordsPruned":true,"earlierRecordsUnavailable":false,"retention":{"days":30,"records":400}}""",
                                        )
                                    }
                                    "schedule/edit" -> {
                                        assertEquals(
                                            "revision",
                                            input!!.string("expectedUpdatedAt"),
                                        )
                                        if (input.string("action") == "delete")
                                            parseObject(
                                                """{"id":"task","code":"schedule_conflict"}""",
                                            )
                                        else {
                                            assertEquals(
                                                parseObject("""{"title":"Renamed"}"""),
                                                input["patch"],
                                            )
                                            task
                                        }
                                    }
                                    else -> error("Unexpected schedule method")
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
            ProtocolClient(transport).use { client ->
                val created =
                    client.createSchedule(
                        " Reminder ",
                        "Review",
                        parseObject("""{"kind":"every","every_seconds":3600}"""),
                        parseObject("""{"kind":"new_once","create":{"cwd":"/project"}}"""),
                    )
                assertEquals("reserved", created.targetSessionId)
                assertTrue(created.involvedSessionIds.isEmpty())
                val page = client.listSchedules(offset = 100)
                assertEquals(101, page.total)
                assertEquals(1, page.shown)
                val history = client.scheduleHistory(created.id, "cursor")
                assertEquals("actual", history.runs.single().targetSessionId)
                assertTrue(history.pruned)
                client.editSchedule(
                    created.id,
                    created.updatedAt,
                    parseObject("""{"title":"Renamed"}"""),
                )
                try {
                    client.deleteSchedule(created.id, created.updatedAt)
                    fail("Conflict must remain visible")
                } catch (error: HostRemoteException) {
                    assertEquals("schedule_conflict", error.code)
                }
                val count = methods.size
                ProtocolClient(
                        object : HostTransport by transport {
                            override val supportsScheduleManagement = false
                        },
                    )
                    .use { remote ->
                        try {
                            remote.runSchedule(created.id)
                            fail("Unavailable management must not be sent")
                        } catch (error: HostRemoteException) {
                            assertEquals("SCHEDULE_MANAGEMENT_UNSUPPORTED", error.code)
                        }
                    }
                assertEquals(count, methods.size)
                assertFalse(methods.any { it.startsWith("session/") })
            }
        }
}
