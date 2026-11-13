package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SubscriptionsApiTest {
    @Test
    fun loginStepsExposeInteractionsWithoutReturningOrLoggingTheGrant() = runBlocking {
        val phases = listOf("starting", "open", "device", "prompt", "done", "failed", "cancelled")
        var polls = 0
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            assertEquals("appAuth/poll", input.string("method"))
            assertEquals(
                "attempt",
                input["args"]!!.objectValue().string("attemptId"),
            )
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)

                    putJsonObject("result") {
                        put("phase", phases[polls++])
                        put("url", "https://example.test/authorize?state=synthetic-state")
                        put("userCode", "SYNTHETIC-CODE")
                        put("message", "synthetic-private-prompt")
                        put("placeholder", "synthetic-private-placeholder")
                        put("error", "synthetic-private-error")
                    }
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            for (phase in LoginPhase.entries) {
                val step = client.pollLogin("attempt")
                assertEquals(phase, step.phase)
                assertEquals(phase.ordinal >= LoginPhase.Done.ordinal, step.phase.finished)
                assertEquals("https://example.test/authorize?state=synthetic-state", step.url)
                assertEquals("SYNTHETIC-CODE", step.userCode)
                assertEquals("synthetic-private-prompt", step.message)
                assertEquals("LoginStep(phase=$phase)", step.toString())
            }
        }
    }
}
