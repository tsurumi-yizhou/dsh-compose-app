package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ToolPayloadMappingTest {
    private val json =
        Json.parseToJsonElement(
            """{"text":"hi","ok":true,"n":42,"list":[1,"two",null],"nested":{"a":1}}""",
        )

    @Test
    fun wireJsonBecomesADomainTreeAndBack() {
        val payload = json.toToolPayload()

        assertEquals("hi", payload.text("text"))
        assertEquals(true, payload.field("ok")?.asBoolean)
        assertEquals(42L, payload.field("n")?.asLong)
        assertEquals(
            listOf("1", "two"),
            payload.arrayItems("list")?.mapNotNull { it.asText ?: (it as? ToolPayload.Num)?.raw },
        )
        assertEquals(1L, payload.field("nested")?.field("a")?.asLong)

        assertEquals(json, payload.toJsonElement())
    }

    @Test
    fun absentFieldsAndWrongShapesReadAsNull() {
        val payload = json.toToolPayload()

        assertNull(payload.field("missing"))
        assertNull(payload.text("ok"))
        assertNull(payload.objectFields("text"))
        assertNull(payload.arrayItems("nested"))
        assertNull(ToolPayload.Null.field("x"))
        assertEquals(emptyMap<String, String>(), ToolPayload.Null.textFields())
    }
}
