package dev.fughetta.dshcompose.data.jobs

import dev.fughetta.dshcompose.domain.jobs.*
import dev.fughetta.dshcompose.protocol.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EngineJobsRepositoryTest {
    @Test
    fun streamedOutputAndStopRepliesRemainBoundedAndOwnedByTheirConnection(): Unit = runBlocking {
        withTimeout(20000) {
            val frames = Channel<JsonObject>(4)
            val stopStarted = Channel<Unit>(2)
            val stopReplies = Channel<Unit>(2)
            val stopFinished = Channel<Unit>(2)
            val stopCount = AtomicInteger()
            val followCount = AtomicInteger()
            val cancelled = Channel<String>(8)
            fun job(id: String = "job1", status: String = "running", start: Long = 1) =
                buildJsonObject {
                    put("id", id)
                    put("kind", "bash")
                    put("label", "build")
                    put("status", status)
                    put("startedAt", start)
                    putJsonObject("output") { put("total", 100) }
                }
            fun frame(type: String, job: JsonObject) = buildJsonObject {
                put("type", type)
                put("job", job)
            }
            val server =
                embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                        install(WebSockets)
                        routing {
                            webSocket("/compose/events") {
                                val active = mutableMapOf<String, Job>()
                                suspend fun item(id: String, value: JsonObject) {
                                    send(
                                        buildJsonObject {
                                            put("op", "event")
                                            put("id", id)
                                            put("data", value)
                                        }
                                            .toString(),
                                    )
                                }
                                try {
                                    for (raw in incoming) {
                                        val input =
                                            Json.parseToJsonElement((raw as Frame.Text).readText())
                                                .jsonObject
                                        val id = input.getValue("id").jsonPrimitive.content
                                        if (input["op"]?.jsonPrimitive?.content == "cancel") {
                                            active.remove(id)?.cancel()
                                            cancelled.send(id)
                                            continue
                                        }
                                        val endpoint =
                                            input.getValue("method").jsonPrimitive.content
                                        when (endpoint) {
                                            "\$events" ->
                                                item(
                                                    id,
                                                    buildJsonObject {
                                                        put("type", "ready")
                                                        put("clientId", "client")
                                                        putJsonObject("host") {
                                                            put("home", "/work")
                                                        }
                                                    },
                                                )
                                            "job/list" -> {
                                                assertEquals(
                                                    "s1",
                                                    input
                                                        .getValue("args")
                                                        .jsonObject
                                                        .getValue("request")
                                                        .jsonObject
                                                        .getValue("sessionId")
                                                        .jsonPrimitive
                                                        .content,
                                                )
                                                item(
                                                    id,
                                                    buildJsonObject {
                                                        put("type", "rows")
                                                        putJsonArray("jobs") {
                                                            add(job("ended", "completed", 100))
                                                            add(job("older", start = 0))
                                                            add(job())
                                                        }
                                                    },
                                                )
                                            }
                                            "job/follow" -> active[id] = launch {
                                                    followCount.incrementAndGet()
                                                    item(id, frame("opened", job()))
                                                    for (value in frames) item(id, value)
                                                }
                                        }
                                    }
                                } finally {
                                    active.values.forEach { it.cancel() }
                                }
                            }
                            post("/compose/rpc") {
                                val input = Json.parseToJsonElement(call.receiveText()).jsonObject
                                assertEquals(
                                    "job/kill",
                                    input.getValue("method").jsonPrimitive.content,
                                )
                                val request =
                                    input.getValue("args").jsonObject.getValue("request").jsonObject
                                assertEquals(
                                    "s1",
                                    request.getValue("sessionId").jsonPrimitive.content,
                                )
                                assertEquals(
                                    "job1",
                                    request.getValue("jobId").jsonPrimitive.content,
                                )
                                stopCount.incrementAndGet()
                                stopStarted.send(Unit)
                                stopReplies.receive()
                                call.respondText(
                                    buildJsonObject {
                                        put("id", input.getValue("id"))

                                        putJsonObject("result") { put("outcome", "requested") }
                                    }
                                        .toString(),
                                    ContentType.Application.Json,
                                )
                                stopFinished.send(Unit)
                            }
                        }
                    }
                    .start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = EngineJobsRepository()
            val port = server.engine.resolvedConnectors().single().port
            val client = ProtocolClient(HostConnection("http://127.0.0.1:$port"))
            val outputs = MutableStateFlow<JobOutput?>(null)
            var follow: Job? = null
            var roster: Job? = null
            suspend fun output(predicate: (JobOutput) -> Boolean) =
                outputs.filterNotNull().first(predicate)
            fun openOutput(operations: JobsRepository, job: JobInfo) {
                follow?.cancel()
                outputs.value = null
                follow = scope.launch {
                    operations.output("s1", job).collect { outputs.value = it }
                }
            }
            try {
                client.mux.ready()
                repository.connect(client, scope)
                val operations = repository.connection.value!!
                val rows = MutableStateFlow(JobRoster())
                roster = scope.launch { operations.watch("s1").collect { rows.value = it } }
                val snapshot = rows.first { !it.loading && it.jobs.isNotEmpty() }
                assertEquals(listOf("job1", "older", "ended"), snapshot.jobs.map { it.id })
                assertTrue(operations.canStop)
                val selectedJob = snapshot.jobs.first()
                openOutput(operations, selectedJob)
                output { !it.loading }
                frames.send(
                    buildJsonObject {
                        put("type", "output")
                        putJsonArray("chunks") {
                            addJsonObject {
                                put(
                                    "text",
                                    (0 until 12000).joinToString("\n", postfix = "\n") {
                                        "line$it"
                                    },
                                )
                                put("channel", "stdout")
                            }
                        }
                    },
                )
                val bounded = output { it.lines.lastOrNull() == "line11999" }
                assertEquals(1000, bounded.lines.size)
                assertEquals(11000L, bounded.firstLine)
                assertTrue(bounded.trimmed)
                assertEquals(0, stopCount.get())
                val stop = scope.async { operations.stop("s1", "job1") }
                stopStarted.receive()
                assertEquals("running", outputs.value!!.job.status)
                stopReplies.send(Unit)
                assertTrue(stop.await())
                stopFinished.receive()
                assertEquals("running", outputs.value!!.job.status)
                frames.send(frame("status", job(status = "terminated")))
                output { !it.job.live }
                follow?.cancelAndJoin()
                cancelled.receive()
                openOutput(operations, selectedJob)
                output { !it.loading }
                assertEquals(2, followCount.get())
                assertTrue(outputs.value!!.lines.isEmpty())
                val lateStop = scope.async { operations.stop("s1", "job1") }
                stopStarted.receive()
                repository.connect(null, scope)
                assertNull(repository.connection.value)
                assertFalse(operations.active)
                stopReplies.send(Unit)
                stopFinished.receive()
                try {
                    lateStop.await()
                    fail("Retired replies must not escape their connection")
                } catch (_: CancellationException) {}
                repository.connect(client, scope)
                val next = repository.connection.value!!
                assertNotSame(operations, next)
                val nextRows = next.watch("s1").first { !it.loading }
                assertEquals(snapshot.jobs.map { it.id }, nextRows.jobs.map { it.id })
            } finally {
                repository.close()
                follow?.cancel()
                roster?.cancel()
                scope.cancel()
                client.close()
                server.stop(0, 1000)
            }
        }
    }
}
