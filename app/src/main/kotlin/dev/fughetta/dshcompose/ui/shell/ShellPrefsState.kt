package dev.fughetta.dshcompose.ui.shell

import kotlinx.collections.immutable.*

/** Feature ids ([FeatureCatalog]) the quick panel starts with. */
val QuickPanelDefaults =
    dev.fughetta.dshcompose.domain.settings.DefaultQuickPanelItems.toPersistentList()

/** Shell-wide conveniences kept on this phone only. */
data class ShellPrefsState(
    val quickPanelSwipe: Boolean = true,
    val quickPanelItems: PersistentList<String> = QuickPanelDefaults,
    /** Medium-level risk notices the user asked not to see again. */
    val silencedRisks: PersistentSet<String> = persistentSetOf(),
    val recentSearches: PersistentList<String> = persistentListOf(),
    val backgroundGuideSeen: Boolean = false,
)

sealed interface ShellPrefsAction {
    data class QuickPanelSwipe(val enabled: Boolean) : ShellPrefsAction

    data class QuickPanelItems(val items: List<String>) : ShellPrefsAction

    data class SilenceRisk(val id: String) : ShellPrefsAction

    data class RememberSearch(val query: String) : ShellPrefsAction

    data object ClearSearches : ShellPrefsAction

    data object BackgroundGuideSeen : ShellPrefsAction
}
