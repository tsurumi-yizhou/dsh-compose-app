package dev.fughetta.dshcompose.ui.nativeplugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativePluginBindingsTest {
    @Test
    fun formulasAndBindingsRemainDataWithoutInterpretingBoundPayloads() {
        val data =
            Json.parseToJsonElement("""{"price":12,"count":3,"payload":{"${'$'}":"data.price"}}""")
        val view =
            Json.parseToJsonElement(
                """{"type":"metric","value":{"=":"clamp(data.price * data.count, 0, 30)"},"caption":{"${'$'}":"data.payload"}}""",
            )
        val result = nativePluginResolve(view, data).jsonObject
        assertEquals(30.0, result["value"]?.jsonPrimitive?.doubleOrNull)
        assertEquals(data.jsonObject["payload"], result["caption"])
        assertNull(nativePluginFormula("data.price.constructor()", data))
        assertNull(nativePluginFormula("1 / 0", data))
        assertNull(nativePluginFormula("abs(".repeat(30) + "1" + ")".repeat(30), data))
    }

    @Test
    fun localEditsCannotEscapeTheBoundModelAndMergeUpdatesDeleteKeys() {
        val data = Json.parseToJsonElement("""{"profile":{"name":"Old","count":2},"other":7}""")
        assertSame(data, nativePluginWrite(data, "data.__proto__.name", JsonPrimitive("Bad")))
        assertSame(data, nativePluginWrite(data, "data.rows[99999999]", JsonPrimitive("Bad")))
        val changed = nativePluginWrite(data, "data.profile.name", JsonPrimitive("New"))
        assertEquals("Old", nativePluginLookup(data, "data.profile.name").jsonPrimitive.content)
        assertEquals("New", nativePluginLookup(changed, "data.profile.name").jsonPrimitive.content)
        assertEquals(7, nativePluginLookup(changed, "data.other").jsonPrimitive.int)
    }
}
