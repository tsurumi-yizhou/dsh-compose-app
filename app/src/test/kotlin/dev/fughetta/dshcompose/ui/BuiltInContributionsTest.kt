package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.ui.*
import org.junit.Assert.*
import org.junit.Test

class BuiltInContributionsTest {
    @Test
    fun sessionActionsBindTheGivenSession() {
        for (placement in
            listOf(
                FeaturePlacement.SessionContent,
                FeaturePlacement.SessionTools,
                FeaturePlacement.SessionManage,
            )) {
            for (entry in builtInContributions.at(placement)) {
                assertTrue(entry.id, entry.target("s1") is FeatureTarget.Action)
            }
        }
        assertEquals(
            FeatureTarget.Action(AppAction.OpenSessionArtifacts("s1")),
            builtInContributions.entries
                .first { it.id == "session.transcript_artifacts" }
                .target("s1"),
        )
        assertNull(
            builtInContributions.entries
                .first { it.id == "session.permissions_title" }
                .target(null),
        )
    }

    @Test
    fun settingsGroupsAreContiguousAndInPageOrder() {
        val settings = builtInContributions.at(FeaturePlacement.Settings)
        assertTrue(settings.filter { it.section == null }.isEmpty())
        // Registration order follows the page: each group once, in [SettingsSection] order.
        val groups = settings.mapNotNull { it.section }
        assertEquals(groups.sortedBy { it.ordinal }, groups)
        assertEquals(SettingsSection.General, settings.first().section)
        assertEquals(SettingsSection.Help, settings.last().section)
        for (section in SettingsSection.entries) assertTrue(section.name, section in groups)
        assertTrue(
            builtInContributions.entries
                .filter { it.placement != FeaturePlacement.Settings }
                .all { it.section == null },
        )
    }
}
