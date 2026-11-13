package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelParametersTest {
    @Test
    fun editingParametersPreservesUntouchedProtocolValuesAndCanClearOverrides() {
        val raw =
            parseObject(
                """{"id":"model","name":"Original","contextWindow":128000,"maxTokens":8192,"input":["text","image","audio"],"reasoningEfforts":{"off":null,"low":"vendor-low","high":32768},"vendorOption":{"enabled":true}}""",
            )
        val source = ProviderModelSpec.fromJson(raw)
        val edited =
            source.withParameters(
                " Renamed ",
                parseProviderTokenCount("1.5M"),
                null,
                efforts = setOf("off", "low", "max"),
            )
        val saved = edited.toJson()
        assertEquals("Renamed", edited.name)
        assertEquals(1_500_000L, edited.contextWindow)
        assertFalse(saved.containsKey("maxTokens"))
        assertEquals(raw["input"], saved["input"])
        assertEquals(raw["vendorOption"], saved["vendorOption"])
        assertEquals(
            parseObject("""{"off":null,"low":"vendor-low","max":"max"}"""),
            saved["reasoningEfforts"],
        )
        val disabled =
            edited.withParameters(
                null,
                null,
                parseProviderTokenCount("8_192"),
                images = false,
                efforts = emptySet(),
            )
        assertEquals(JsonPrimitive(false), disabled.toJson()["reasoningEfforts"])
        assertEquals(listOf("text"), disabled.input)
        assertNull(disabled.name)
        assertNull(disabled.contextWindow)
        assertEquals(8192L, disabled.maxTokens)
        assertEquals(raw, source.toJson())
        assertTrue(edited.sameSettings(ProviderModelSpec.fromJson(saved)))
    }
}
