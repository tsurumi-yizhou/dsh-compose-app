package dev.fughetta.dshcompose.ui

import androidx.annotation.StringRes
import dev.fughetta.dshcompose.core.designsystem.R as CoreDesignsystemR
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** Navigation owned by the app shell; rendering remains in the existing pages. */
enum class FeaturePage {
    Engine,
    About,
    Licenses,
    Providers,
    HostServices,
    Presets,
    Phone,
    Configuration,
    Plugins,
    NativePlugins,
    Notifications,
    Search,
    Personalization,
    QuickPanel,
    AskAi,
}

sealed interface FeatureTarget {
    data class Action(val action: AppAction) : FeatureTarget

    data class Page(val page: FeaturePage) : FeatureTarget

    /** Actions go to the app; pages to the shell's navigation. */
    fun open(onAction: (AppAction) -> Unit, onPage: (FeaturePage) -> Unit) =
        when (this) {
            is Action -> onAction(action)
            is Page -> onPage(page)
        }
}

enum class FeaturePlacement {
    Settings,
    SessionContent,
    SessionTools,
    SessionManage,
}

/** Groups of the settings page, in the order it shows them; a settings entry without one leads. */
enum class SettingsSection(@StringRes val title: Int) {
    General(CoreDesignsystemR.string.settings_section_general),
    Hosts(CoreDesignsystemR.string.settings_section_hosts),
    Ai(CoreDesignsystemR.string.settings_section_ai),
    Capabilities(CoreDesignsystemR.string.settings_section_capabilities),
    Display(CoreDesignsystemR.string.settings_section_display),
    Advanced(CoreDesignsystemR.string.settings_section_advanced),
    Tools(CoreDesignsystemR.string.settings_section_tools),
    Help(CoreDesignsystemR.string.settings_section_help),
}

class FeatureContribution(
    val id: String,
    val placement: FeaturePlacement,
    val label: Int,
    val target: (String?) -> FeatureTarget?,
    val supportingLabel: Int? = null,
    val section: SettingsSection? = null,
)

/** Ordered contributions; a session entry has no target until a session is selected. */
class FeatureContributions(val entries: PersistentList<FeatureContribution> = persistentListOf()) {
    init {
        require(entries.map { it.id }.distinct().size == entries.size)
    }

    fun at(placement: FeaturePlacement) = entries.filter { it.placement == placement }
}
