package dev.fughetta.dshcompose.domain.settings

val DefaultQuickPanelItems = listOf("plugins", "engine", "settings")

data class ShellPreferences(
    val quickPanelSwipe: Boolean = true,
    val quickPanelItems: List<String> = DefaultQuickPanelItems,
    val silencedRisks: Set<String> = emptySet(),
    val recentSearches: List<String> = emptyList(),
    val backgroundGuideSeen: Boolean = false,
) {
    companion object {
        const val RecentLimit = 5
    }
}
