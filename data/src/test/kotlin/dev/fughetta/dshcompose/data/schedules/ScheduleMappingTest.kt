package dev.fughetta.dshcompose.data.schedules

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import dev.fughetta.dshcompose.domain.schedules.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScheduleMappingTest {
    @Test
    fun metadataEditsRetainTheEngineRuleDestinationAndOptimisticRevision() = runBlocking {
        val task =
            Json.parseToJsonElement(
                    """{"id":"task","title":"Reminder","prompt":"Review","kind":"rrule","rrule":"FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1","status":"active","target":{"kind":"new_once","sessionId":"reserved","create":{"cwd":"/project","agentPreset":"reviewer"}},"involvedSessionIds":["delivered"],"scheduledAt":"2026-11-08T10:00:00Z","updatedAt":"revision"}""",
                )
                .jsonObject
        val host = ProtocolHost { method, args ->
            val input = args.getValue("request").jsonObject
            val result =
                when (method) {
                    "schedule/list" ->
                        buildJsonObject {
                            put("items", JsonArray(listOf(task)))
                            put("total", 1)
                            put("offset", 0)
                            put("shown", 1)
                        }
                    "schedule/edit" -> {
                        assertEquals(
                            "revision",
                            input["expectedUpdatedAt"]!!.jsonPrimitive.content,
                        )
                        assertEquals(
                            buildJsonObject { put("title", "Renamed") },
                            input["patch"],
                        )
                        JsonObject(task + ("title" to JsonPrimitive("Renamed")))
                    }
                    else -> error(method)
                }
            result
        }
        host.use {
            val client = host.client
            client.mux.ready()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineSchedulesRepository()
            repository.connect(client, scope)
            val operations = repository.connection.value!!
            try {
                val original = operations.list(null, 0).items.single()
                assertEquals(ScheduleRepeat.Custom, original.timing.repeat)
                assertEquals("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1", original.timing.customRule)
                assertEquals(ScheduleTargetKind.NewOnce, original.target.kind)
                assertEquals("reserved", original.target.sessionId)
                assertEquals("/project", original.target.cwd)
                assertEquals(listOf("delivered"), original.involvedSessionIds)
                val save = SaveScheduleUseCase(operations) { Instant.parse("2026-11-09T00:00:00Z") }
                val changed =
                    save(
                        ScheduleInput("Renamed", original.prompt, original.timing, original.target),
                        original,
                    )
                assertEquals("Renamed", changed.title)
                assertEquals(original.timing, changed.timing)
                assertEquals(original.target, changed.target)
            } finally {
                repository.close()
                scope.cancel()
            }
        }
    }
}
