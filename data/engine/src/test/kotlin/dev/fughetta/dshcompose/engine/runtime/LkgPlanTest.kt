package dev.fughetta.dshcompose.engine.runtime

import org.junit.Assert.*
import org.junit.Test

class LkgPlanTest {
    @Test
    fun oldSnapshotRestoresComposeAppWithoutReintroducingWebApp() {
        val good =
            ProfileSnapshot(
                listOf(
                    "@deepseek-ai/dsh-base",
                    "@deepseek-ai/dsh-web-app",
                    "user-bundle",
                    "dsh-compose-app",
                ),
                setOf("user-bundle"),
                mapOf("user-bundle" to "1"),
                null,
            )
        val now = good.copy(bundles = listOf("@deepseek-ai/dsh-base", "dsh-compose-app"))
        val plan = LkgPlan.of(good, now)
        assertEquals(listOf("@deepseek-ai/dsh-base", "dsh-compose-app", "user-bundle"), plan.target)
        assertEquals(listOf("user-bundle"), plan.enable)
        assertTrue(plan.disable.isEmpty())
        assertTrue(plan.bundlesChanged)
        assertTrue(LkgPlan.of(good, now.copy(bundles = plan.target)).isEmpty)
    }

    @Test
    fun restoresAvailableSameVersionBundlesWithoutDowngrading() {
        val good =
            ProfileSnapshot(
                listOf("core", "same", "upgraded", "removed"),
                setOf("same", "upgraded", "removed"),
                mapOf("same" to "1", "upgraded" to "1", "removed" to "1"),
                "setting: good",
            )
        val now =
            ProfileSnapshot(
                listOf("core", "upgraded", "new"),
                setOf("same", "upgraded", "new"),
                mapOf("same" to "1", "upgraded" to "2", "new" to "1"),
                "setting: bad",
            )
        val plan = LkgPlan.of(good, now)
        assertEquals(listOf("core", "same"), plan.target)
        assertEquals(listOf("upgraded", "new"), plan.disable)
        assertEquals(listOf("same"), plan.enable)
        assertEquals(listOf("upgraded"), plan.changed)
        assertTrue(plan.patchChanged)
        assertFalse(plan.isEmpty)
        assertTrue(LkgPlan.of(good, good).isEmpty)
    }

    @Test
    fun emptyPatchesAreEquivalentAndMeaningfulContentIsNot() {
        assertTrue(LkgPlan.samePatch(null, "# comment\n[]\n"))
        assertTrue(LkgPlan.samePatch("x: true\n", "x: true"))
        assertFalse(LkgPlan.samePatch("x: true", "x: false"))
    }
}
