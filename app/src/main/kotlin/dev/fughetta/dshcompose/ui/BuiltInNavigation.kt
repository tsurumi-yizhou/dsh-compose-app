package dev.fughetta.dshcompose.ui

import androidx.navigation3.runtime.NavKey

/** These declarations drive app navigation and the generated guide. */
internal val builtInPages =
    listOf(
        page("conversations") { Conversations },
        page("settings", "general") { Settings },
        page("engine", "diagnostics", menu = FeaturePage.Engine) { Engine },
        page("remote", "compose-services", menu = FeaturePage.HostServices) { HostServices },
        page("account") { DeepSeekAccount },
        page("providers", menu = FeaturePage.Providers) { Providers },
        page("plugins", menu = FeaturePage.Plugins) { PluginManagement },
        page("plugin-inventory") { PluginSeats },
        page("notifications", menu = FeaturePage.Notifications) { NotificationSettings },
        page("native-plugins", menu = FeaturePage.NativePlugins) { NativePluginPreferences },
        page("phone", menu = FeaturePage.Phone) { PhoneControls() },
        page("phone-dnd") { PhoneControls(dev.fughetta.dshcompose.ui.phone.PhoneSubpage.Dnd) },
        page("phone-unlocks") {
            PhoneControls(dev.fughetta.dshcompose.ui.phone.PhoneSubpage.Unlocks)
        },
        page("phone-background-guide") {
            PhoneControls(dev.fughetta.dshcompose.ui.phone.PhoneSubpage.BackgroundGuide)
        },
        page("agent-presets", menu = FeaturePage.Presets) { AgentPresets },
        page(
            "agent-loop",
            "shell-settings",
            "web-search",
            "subagent-settings",
            "cot-translation",
            menu = FeaturePage.Configuration,
        ) {
            EngineConfiguration
        },
        page("cordis") { Cordis },
        page("workspaces") { Workspaces },
        page("search") { SessionSearch },
        page("models") { Providers },
        page("appearance") { Appearance },
        page("schedules") { Schedules },
        page("engine-settings") { EngineConfiguration },
        page("phone-confirm", "phone-files") { PhoneControls() },
        page("personalization", "reply-prefs", menu = FeaturePage.Personalization) { ReplyPrefs },
        page("settings-search", menu = FeaturePage.Search) { SettingsSearch },
        page("quick-panel", menu = FeaturePage.QuickPanel) { QuickPanelSettings },
        page("about", menu = FeaturePage.About) { About },
        page("licenses", menu = FeaturePage.Licenses) { Licenses },
    )

internal fun page(
    vararg ids: String,
    menu: FeaturePage? = null,
    destination: () -> NavKey,
) = AppModuleContribution.Page(ids.toList(), menu, PageTarget.Navigation(destination))
