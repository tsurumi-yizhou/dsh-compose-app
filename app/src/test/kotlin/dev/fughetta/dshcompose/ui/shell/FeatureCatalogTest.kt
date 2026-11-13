package dev.fughetta.dshcompose.ui.shell

import org.junit.Assert.*
import org.junit.Test

class FeatureCatalogTest {
    @Test
    fun normalizeFoldsWidthCaseAndSpaces() {
        assertEquals("apikey", normalizeQuery("ＡＰＩ　Key"))
        assertEquals("深色模式", normalizeQuery(" 深色 模式 "))
    }

    @Test
    fun ranksTitleAboveKeywordAndMatchesEitherLanguage() {
        val a = featureEntry("appearance")!!
        val v = featureEntry("notifications")!!
        val texts =
            mapOf(
                a to
                    listOf(
                        FeatureText("外观", listOf("深色模式", "主题"), "设置"),
                        FeatureText("Appearance", listOf("dark mode", "theme"), "Settings"),
                    ),
                v to listOf(FeatureText("通知", listOf("外观无关"), "设置")),
            )
        assertEquals(listOf(a, v), rankFeatures("外观", texts))
        assertEquals(listOf(a), rankFeatures("Dark Mode", texts))
        assertEquals(listOf(a, v), rankFeatures("设置", texts))
        assertTrue(rankFeatures("  ", texts).isEmpty())
        assertTrue(rankFeatures("nothing", texts).isEmpty())
    }

    @Test
    fun catalogIdsAreUnique() {
        assertEquals(FeatureCatalog.size, FeatureCatalog.map { it.id }.toSet().size)
    }
}
