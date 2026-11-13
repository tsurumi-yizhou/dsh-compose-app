package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandsTest {
    @Test
    fun commandCatalogAndExecutionUseDistinctRoutesAndCompactionOwnsItsLongDeadline() =
        runBlocking {
            val timeouts = mutableListOf<Long>()
            val lines = mutableListOf<String>()
            val engine = MockEngine { request ->
                val input = parseObject((request.body as TextContent).text)
                val args = input["args"]!!.objectValue()
                assertEquals("session", args.string("agentId"))
                val value =
                    when (input.string("method")) {
                        "commands/list" ->
                            wireJson.parseToJsonElement(
                                """[
                    {"name":"compact","definitionId":"@deepseek-ai/dsh-command-compact","description":"Compress"},
                    {"name":"plan","input":{"hint":"objective","attachments":true}},
                    {"name":"export"},{"name":"custom","definitionId":"plugin"} ]""",
                            )
                        "commands/execute" -> {
                            lines.add(args.requiredString("line"))
                            assertEquals(JsonArray(emptyList()), args["submittedAttachments"])
                            parseObject(
                                """{"commandId":"one","result":{"kind":"success","text":"Confirmed"}}""",
                            )
                        }
                        else -> error("Unexpected RPC")
                    }
                respond(
                    buildJsonObject {
                        put("id", input["id"]!!)

                        put("result", value)
                    }
                        .toString(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val base = HostConnection("http://localhost", engine = engine)
            val transport =
                object : HostTransport by base {
                    override suspend fun callWithTimeout(
                        endpoint: String,
                        args: JsonObject,
                        timeoutMillis: Long,
                    ): JsonElement {
                        timeouts.add(timeoutMillis)
                        return base.callWithTimeout(endpoint, args, timeoutMillis)
                    }
                }
            ProtocolClient(transport).use { client ->
                val host = client.listCommands("session")
                assertFalse(host[0].takesInput)
                assertTrue(host[1].takesInput)
                assertTrue(host[1].acceptsAttachments)
                assertEquals("one", client.executeCommand("session", " /compact ").commandId)
                assertTrue(client.executeCommand("session", "/plan objective").success)
                assertEquals(listOf(1_800_000L, 65_000L), timeouts)
                assertEquals(listOf("/compact", "/plan objective"), lines)
            }
        }

    @Test
    fun acknowledgementsAndDurableCommandRecordsMergeWithoutChangingPublishedHistoryOrDuplicatingRows() {
        fun event(seq: Long, type: String, data: String) =
            SessionUpdate.Record(SessionRecord(seq, seq, type, parseObject(data)))
        val transcript = ChatTranscript()
        transcript.apply(
            event(
                1,
                "user/message",
                """{"source":{"kind":"user"},"content":[{"type":"text","text":"Keep"}]}""",
            ),
        )
        val original = transcript.items.single()
        transcript.confirmCommand(CommandResult("first", true, "Ack first"), "/compact")
        assertEquals(2, transcript.items.size)
        transcript.apply(event(2, "command/run", """{"commandId":"first","name":"compact"}"""))
        assertEquals(2, transcript.items.size)
        assertSame(original, transcript.items.first())
        assertTrue(transcript.items.last().command!!.done)
        transcript.apply(
            event(
                3,
                "command/done",
                """{"commandId":"first","kind":"success","text":"Durable result"}""",
            ),
        )
        val before = transcript.items
        transcript.apply(
            event(4, "command/run", """{"commandId":"second","name":"plan","args":" off"}"""),
        )
        assertFalse(transcript.items.last().command!!.done)
        transcript.apply(
            event(5, "command/done", """{"commandId":"second","kind":"error","text":"Rejected"}"""),
        )
        transcript.confirmCommand(CommandResult("second", false, "Rejected"), "/plan off")
        assertEquals(3, transcript.items.size)
        assertEquals("/plan off", transcript.items.last().command!!.line)
        assertFalse(transcript.items.last().command!!.success)
        assertEquals("Durable result", before.last().text)
        assertEquals(2, before.size)
        transcript.prependHistory(
            listOf(SessionRecord(0, 0, "turn/start", JsonObject(emptyMap()))),
            false,
        )
        assertEquals(3, transcript.items.size)
        assertEquals("Rejected", transcript.items.last().text)
        transcript.confirmCommand(CommandResult(null, false, "Unknown command"), "/missing")
        assertEquals(4, transcript.items.size)
        assertFalse(transcript.items.last().command!!.success)
        val count = transcript.items.size
        repeat(1000) { index ->
            transcript.apply(
                event(
                    6 + index.toLong(),
                    "tool/call",
                    """{"callId":"tool$index","name":"read","arguments":"{}"}""",
                ),
            )
        }
        assertEquals(count + 1000, transcript.items.size)
        assertEquals(1, transcript.items.count { it.command?.id == "first" })
    }
}
