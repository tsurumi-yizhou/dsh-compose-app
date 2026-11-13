package dev.fughetta.dshcompose.domain.providers

import org.junit.Assert.*
import org.junit.Test

class ProviderParametersTest {
    @Test
    fun tokenCountsSupportScaledIntegersAndRejectInvalidOrOverflowingValues() {
        assertEquals(128000L, parseProviderTokenCount("128K"))
        assertNull(parseProviderTokenCount("1.5"))
        assertNull(parseProviderTokenCount("0"))
        assertNull(parseProviderTokenCount("9223372036854775808"))
    }

    @Test
    fun changingThinkingPreservesSelectedValuesAndRestoringTheDraftClearsItsChanges() {
        val model =
            ProviderModelSpec.create("model")
                .withParameters("Original", 128000, 8192, efforts = setOf("off", "low", "high"))
        val changed =
            model.withParameters(" Renamed ", 1500000, null, efforts = setOf("off", "low", "max"))
        assertEquals("Renamed", changed.name)
        assertEquals(model.reasoning!!.values["low"], changed.reasoning!!.values["low"])
        assertEquals(ProviderEffortValue(level = "max"), changed.reasoning.values["max"])
        assertNull(changed.maxTokens)
        val disabled = changed.withParameters("Original", 128000, 8192, efforts = emptySet())
        assertFalse(disabled.thinks)
        assertTrue(disabled.reasoningLevels.isEmpty())
        assertTrue(
            model.sameSettings(
                disabled.withParameters(
                    "Original",
                    128000,
                    8192,
                    efforts = setOf("off", "low", "high"),
                ),
            ),
        )
    }

    @Test
    fun closingCredentialInputErasesTheOwnedBufferAndRefusesFurtherReads() {
        val key = ProviderKey.from("synthetic-input")
        val owned = key.useCharacters { it }
        assertEquals("ProviderKey(<redacted>)", key.toString())
        key.close()
        assertTrue(owned.all { it == '\u0000' })
        try {
            key.useCharacters { fail("Closed input must not be read") }
            fail()
        } catch (_: IllegalStateException) {}
    }
}
