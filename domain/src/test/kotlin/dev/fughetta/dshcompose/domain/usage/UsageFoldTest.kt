package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import org.junit.Assert.*
import org.junit.Test

class UsageFoldTest {
    private fun projections(
        seq: Long,
        tokens: Map<String, ToolPayload> = emptyMap(),
        pressure: Map<String, ToolPayload> = emptyMap(),
    ) =
        ToolPayload.Obj(
            mapOf(
                "asOfSeq" to ToolPayload.Num(seq.toString()),
                "values" to
                    ToolPayload.Obj(
                        buildMap {
                            if (tokens.isNotEmpty()) put("tokenUsage", ToolPayload.Obj(tokens))
                            if (pressure.isNotEmpty())
                                put("contextPressure", ToolPayload.Obj(pressure))
                        },
                    ),
            ),
        )

    @Test
    fun laterSequenceWinsPerGroupAndClearsOmittedGroups() {
        val fold = UsageFold()
        fold.seed(
            projections(
                seq = 1,
                tokens = mapOf("uncachedInputTokens" to ToolPayload.Num("10")),
                pressure = mapOf("contextWindow" to ToolPayload.Num("1000")),
            ),
        )
        fold.seed(
            projections(
                seq = 2,
                tokens = mapOf("outputTokens" to ToolPayload.Num("5")),
            ),
        )
        val usage = fold.usage()
        assertEquals("tokenUsage group is merged as a whole", 0L, usage.uncachedInput)
        assertEquals("newer token key is merged", 5L, usage.output)
        assertNull("a newer snapshot that omits a group clears it", usage.contextWindow)
    }

    @Test
    fun olderSequenceIsIgnoredForKeysItCarries() {
        val fold = UsageFold()
        fold.seed(projections(seq = 5, tokens = mapOf("outputTokens" to ToolPayload.Num("9"))))
        fold.seed(projections(seq = 3, tokens = mapOf("outputTokens" to ToolPayload.Num("1"))))
        assertEquals(9L, fold.usage().output)
    }

    @Test
    fun replaceClearsPreviousKeys() {
        val fold = UsageFold()
        fold.seed(projections(seq = 1, tokens = mapOf("outputTokens" to ToolPayload.Num("9"))))
        fold.seed(
            projections(seq = 1, pressure = mapOf("pressureTokens" to ToolPayload.Num("4"))),
            replace = true,
        )
        val usage = fold.usage()
        assertEquals("replace drops keys absent from the snapshot", 0L, usage.output)
        assertEquals(4L, usage.pressureTokens)
    }

    @Test
    fun nullSeqYieldsEmptyUsage() {
        val fold = UsageFold()
        fold.seed(ToolPayload.Obj(mapOf("values" to ToolPayload.Obj(emptyMap()))))
        assertEquals(0L, fold.usage().output)
        assertNull(fold.usage().contextWindow)
    }
}
