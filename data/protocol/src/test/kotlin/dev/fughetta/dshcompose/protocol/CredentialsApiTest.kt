package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CredentialsApiTest {
    @Test
    fun providerReferencesAndHostCredentialStatusDriveTheSetAndUnsetWorkflow() = runBlocking {
        val fixtureKey = "synthetic-request-only-value"
        var configured = false
        var writes = 0
        var referenceReads = 0
        val engine = MockEngine { request ->
            val input = parseObject((request.body as TextContent).text)
            val args = input["args"]!!.objectValue()
            var refused = false
            val value =
                when (input.string("method")) {
                    "settings/describe" ->
                        parseObject(
                            """{"namespaces":[{"ns":"llm-pi-ai","revision":1,"value":{"providers":{
                    "gateway":{"displayName":"Gateway","apiKeyEnv":"GATEWAY_API_KEY","headers":{"Authorization":"synthetic-private-header"}},
                    "subscription":{"displayName":"Subscription"}
                }}}]}""",
                        )
                    "credentials/describe" -> {
                        referenceReads++
                        val refs = (args["refs"] as JsonArray).map { it.jsonPrimitive.content }
                        assertEquals(refs.size, refs.distinct().size)
                        buildJsonObject {
                            refs.forEach { ref ->
                                putJsonObject(ref) {
                                    put(
                                        "configured",
                                        if (ref == "ENV_ONLY_KEY") true else configured,
                                    )
                                    put("writable", ref != "ENV_ONLY_KEY")
                                    if (ref == "ENV_ONLY_KEY") put("source", "env")
                                    else if (configured) put("source", "file")
                                    else put("source", JsonNull)
                                }
                            }
                        }
                    }
                    "credentials/set" -> {
                        writes++
                        assertEquals(fixtureKey, args.string("value"))
                        if (args.string("ref") == "ENV_ONLY_KEY") refused = true
                        else {
                            assertEquals("GATEWAY_API_KEY", args.string("ref"))
                            configured = true
                        }
                        JsonNull
                    }
                    "credentials/unset" -> {
                        assertEquals("GATEWAY_API_KEY", args.string("ref"))
                        configured = false
                        JsonNull
                    }
                    else -> error("Unexpected credential read or replay")
                }
            respond(
                buildJsonObject {
                    put("id", input["id"]!!)
                    if (refused) putJsonObject("error") { put("code", "credential/rejected") }
                    else put("result", value)
                }
                    .toString(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        ProtocolClient(HostConnection("http://localhost:3080", engine = engine)).use { client ->
            val accounts = client.providerAccounts()
            assertEquals("DEEPSEEK_API_KEY", accounts.first().credentialRef)
            assertEquals("GATEWAY_API_KEY", accounts[1].credentialRef)
            assertNull(accounts[2].credentialRef)
            assertFalse(accounts.toString().contains("synthetic-private-header"))
            val refs = listOf("GATEWAY_API_KEY", "ENV_ONLY_KEY", "GATEWAY_API_KEY")
            assertFalse(client.describeCredentials(refs).getValue("GATEWAY_API_KEY").configured)
            val key = ProviderKey.from(fixtureKey)
            assertFalse(key.toString().contains(fixtureKey))
            key.use { client.setProviderKey("GATEWAY_API_KEY", it) }
            try {
                key.text()
                fail()
            } catch (_: IllegalStateException) {}
            val status = client.describeCredentials(refs)
            assertTrue(status.getValue("GATEWAY_API_KEY").configured)
            assertEquals("file", status.getValue("GATEWAY_API_KEY").source)
            assertFalse(status.getValue("ENV_ONLY_KEY").writable)
            try {
                ProviderKey.from(fixtureKey).use { client.setProviderKey("ENV_ONLY_KEY", it) }
                fail()
            } catch (error: HostRemoteException) {
                assertEquals("credential/rejected", error.code)
            }
            client.clearProviderKey("GATEWAY_API_KEY")
            assertFalse(client.describeCredentials(refs).getValue("GATEWAY_API_KEY").configured)
            assertEquals(2, writes)
            assertEquals(3, referenceReads)
        }
    }
}
