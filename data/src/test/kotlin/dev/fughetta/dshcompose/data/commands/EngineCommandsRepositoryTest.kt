package dev.fughetta.dshcompose.data.commands

import dev.fughetta.dshcompose.data.testing.ProtocolHost
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandCatalogsTest {
    private class Request(val session: String, val line: String? = null) {
        val reply = CompletableDeferred<JsonElement>()
        val ended = CompletableDeferred<Boolean>()

        suspend fun await(): JsonElement =
            try {
                reply.await()
            } finally {
                ended.complete(!currentCoroutineContext().isActive)
            }
    }

    private class Host : AutoCloseable {
        val reads = Channel<Request>(Channel.UNLIMITED)
        val executions = Channel<Request>(Channel.UNLIMITED)
        val transport = ProtocolHost { endpoint, args ->
            when (endpoint) {
                "commands/list" -> {
                    val request = Request(args.getValue("agentId").jsonPrimitive.content)
                    reads.send(request)
                    request.await()
                }
                "skills/list" -> buildJsonObject { put("skills", JsonArray(emptyList())) }
                "commands/execute" -> {
                    val request =
                        Request(
                            args.getValue("agentId").jsonPrimitive.content,
                            args.getValue("line").jsonPrimitive.content,
                        )
                    executions.send(request)
                    request.await()
                }
                else -> error("Unexpected request: $endpoint")
            }
        }

        suspend fun connect(repository: CommandCatalogs, scope: CoroutineScope) {
            transport.client.mux.ready()
            repository.connect(transport.client, scope)
        }

        override fun close() = transport.close()
    }

    private val catalog =
        Json.parseToJsonElement(
            """[{"name":"plan","description":"","input":{"hint":"objective"}}]""",
        )

    @Test
    fun aChangedQueryReusesTheCatalogWhileSessionSelectionAndInvalidationRetireRequests() =
        runBlocking {
            withTimeout(10000) {
                Host().use { host ->
                    val repository = CommandCatalogs()
                    try {
                        host.connect(repository, this)
                        val owner = repository.connection!!
                        val first = async { owner.catalog("one") }
                        val read = host.reads.receive()
                        first.cancelAndJoin()
                        assertFalse(read.ended.isCompleted)
                        val second = async { owner.catalog("one") }
                        read.reply.complete(catalog)
                        val result = second.await()
                        assertEquals("plan", result.commands.single().name)
                        assertSame(result, owner.catalog("one"))
                        assertTrue(host.reads.tryReceive().isFailure)
                        owner.invalidate()
                        val stale = async { owner.catalog("one") }
                        val staleRead = host.reads.receive()
                        owner.selectSession("two")
                        stale.join()
                        assertTrue(stale.isCancelled)
                        assertTrue(staleRead.ended.await())
                        val next = async { owner.catalog("two") }
                        val nextRead = host.reads.receive()
                        assertEquals("two", nextRead.session)
                        nextRead.reply.complete(catalog)
                        assertEquals(result, next.await())
                    } finally {
                        repository.close()
                    }
                }
            }
        }

    @Test
    fun retiringAConnectionCancelsItsCatalogAndCannotPublishIntoTheNextHost() = runBlocking {
        withTimeout(10000) {
            Host().use { first ->
                Host().use { second ->
                    val repository = CommandCatalogs()
                    try {
                        first.connect(repository, this)
                        val old = repository.connection!!
                        val pending = async { old.catalog("same-session") }
                        val read = first.reads.receive()
                        second.connect(repository, this)
                        pending.join()
                        assertTrue(pending.isCancelled)
                        assertFalse(old.active)
                        assertTrue(read.ended.await())
                        val fresh = async { repository.connection!!.catalog("same-session") }
                        val freshRead = second.reads.receive()
                        read.reply.complete(JsonArray(emptyList()))
                        freshRead.reply.complete(catalog)
                        assertEquals("plan", fresh.await().commands.single().name)
                    } finally {
                        repository.close()
                    }
                }
            }
        }
    }

    @Test
    fun commandsAreExecutedOnTheCapturedConnectionAndRetireWithIt() = runBlocking {
        withTimeout(10000) {
            Host().use { first ->
                Host().use { second ->
                    val repository = CommandCatalogs()
                    try {
                        first.connect(repository, this)
                        val old = repository.connection!!
                        val running = async { old.execute("session", "/plan objective") }
                        val request = first.executions.receive()
                        assertEquals("session", request.session)
                        assertEquals("/plan objective", request.line)
                        second.connect(repository, this)
                        running.join()
                        assertTrue(running.isCancelled)
                        assertTrue(request.ended.await())
                        assertTrue(second.executions.tryReceive().isFailure)
                        val next = async { repository.connection!!.execute("session", "/compact") }
                        val nextRequest = second.executions.receive()
                        assertEquals("session", nextRequest.session)
                        assertEquals("/compact", nextRequest.line)
                        nextRequest.reply.complete(
                            Json.parseToJsonElement(
                                """{"commandId":"confirmed","result":{"kind":"success","text":"done"}}""",
                            ),
                        )
                        assertEquals("confirmed", next.await().commandId)
                    } finally {
                        repository.close()
                    }
                }
            }
        }
    }
}
