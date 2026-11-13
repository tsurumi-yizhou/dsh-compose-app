package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun selectionsRequireARoutableProviderAndAnOfferedReasoningEffort() {
        val model =
            ModelInfo(
                "live",
                "model",
                "Model",
                null,
                persistentListOf(
                    ReasoningEffort("medium", "Medium", null),
                    ReasoningEffort("high", "High", null),
                ),
                "retired",
            )
        val catalog =
            ModelCatalog(
                null,
                persistentSetOf("live"),
                persistentListOf(
                    ProviderGroup("live", "Live", persistentListOf(model)),
                    ProviderGroup(
                        "offline",
                        "Offline",
                        persistentListOf(model.copy(provider = "offline")),
                    ),
                ),
                persistentListOf(ModelCatalogFailure("offline", "Offline")),
            )
        assertEquals("medium", model.initialEffort)
        assertTrue(catalog.accepts(ModelSelection("live", "model", "high")))
        assertTrue(catalog.accepts(ModelSelection("live", "model")))
        assertFalse(catalog.accepts(ModelSelection("offline", "model")))
        assertFalse(catalog.accepts(ModelSelection("live", "model", "unknown")))
        assertFalse(catalog.accepts(ModelSelection("live", "missing")))
    }
}
