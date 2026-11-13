package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.persistentListOf

val builtInContributions =
    FeatureContributions(
        persistentListOf(
            FeatureContribution(
                "settings.navigation_notifications",
                FeaturePlacement.Settings,
                R.string.navigation_notifications,
                { FeatureTarget.Page(FeaturePage.Notifications) },
                section = SettingsSection.General,
            ),
            FeatureContribution(
                "settings.local_engine",
                FeaturePlacement.Settings,
                R.string.settings_local_engine,
                { FeatureTarget.Page(FeaturePage.Engine) },
                section = SettingsSection.Hosts,
            ),
            FeatureContribution(
                "settings.navigation_compose_services",
                FeaturePlacement.Settings,
                R.string.settings_remote_hosts,
                { FeatureTarget.Page(FeaturePage.HostServices) },
                section = SettingsSection.Hosts,
            ),
            FeatureContribution(
                "settings.providers",
                FeaturePlacement.Settings,
                R.string.providers,
                { FeatureTarget.Page(FeaturePage.Providers) },
                section = SettingsSection.Ai,
            ),
            FeatureContribution(
                "settings.permissions_title",
                FeaturePlacement.Settings,
                R.string.settings_default_permissions,
                { FeatureTarget.Action(AppAction.OpenPermissions()) },
                section = SettingsSection.Ai,
            ),
            FeatureContribution(
                "settings.personalization_title",
                FeaturePlacement.Settings,
                R.string.reply_prefs_title,
                { FeatureTarget.Page(FeaturePage.Personalization) },
                section = SettingsSection.Ai,
            ),
            FeatureContribution(
                "settings.agent_presets",
                FeaturePlacement.Settings,
                R.string.agent_presets,
                { FeatureTarget.Page(FeaturePage.Presets) },
                section = SettingsSection.Ai,
            ),
            FeatureContribution(
                "settings.navigation_phone_controls",
                FeaturePlacement.Settings,
                R.string.navigation_phone_controls,
                { FeatureTarget.Page(FeaturePage.Phone) },
                section = SettingsSection.Capabilities,
            ),
            FeatureContribution(
                "settings.navigation_plugins",
                FeaturePlacement.Settings,
                R.string.navigation_plugins,
                { FeatureTarget.Page(FeaturePage.Plugins) },
                section = SettingsSection.Capabilities,
            ),
            FeatureContribution(
                "settings.navigation_media_settings",
                FeaturePlacement.Settings,
                R.string.navigation_media_settings,
                {
                    FeatureTarget.Action(
                        AppAction.Media(dev.fughetta.dshcompose.ui.media.MediaAction.Settings),
                    )
                },
                section = SettingsSection.Capabilities,
            ),
            FeatureContribution(
                "settings.navigation_native_plugins",
                FeaturePlacement.Settings,
                R.string.navigation_native_plugins,
                { FeatureTarget.Page(FeaturePage.NativePlugins) },
                section = SettingsSection.Display,
            ),
            FeatureContribution(
                "settings.appearance",
                FeaturePlacement.Settings,
                R.string.appearance,
                { FeatureTarget.Action(AppAction.OpenAppearance) },
                section = SettingsSection.Display,
            ),
            FeatureContribution(
                "settings.quick_panel_title",
                FeaturePlacement.Settings,
                R.string.quick_panel_title,
                { FeatureTarget.Page(FeaturePage.QuickPanel) },
                section = SettingsSection.Display,
            ),
            FeatureContribution(
                "settings.navigation_engine_configuration",
                FeaturePlacement.Settings,
                R.string.navigation_engine_configuration,
                { FeatureTarget.Page(FeaturePage.Configuration) },
                section = SettingsSection.Advanced,
            ),
            FeatureContribution(
                "settings.navigation_plugin_panels",
                FeaturePlacement.Settings,
                R.string.navigation_plugin_panels,
                { FeatureTarget.Action(AppAction.OpenPluginSeats()) },
                section = SettingsSection.Tools,
            ),
            FeatureContribution(
                "settings.navigation_media_works",
                FeaturePlacement.Settings,
                R.string.navigation_media_works,
                {
                    FeatureTarget.Action(
                        AppAction.Media(dev.fughetta.dshcompose.ui.media.MediaAction.Works()),
                    )
                },
                section = SettingsSection.Tools,
            ),
            FeatureContribution(
                "settings.navigation_terminal",
                FeaturePlacement.Settings,
                R.string.navigation_terminal,
                { FeatureTarget.Action(AppAction.OpenTerminal) },
                section = SettingsSection.Tools,
            ),
            FeatureContribution(
                "settings.navigation_schedules",
                FeaturePlacement.Settings,
                R.string.navigation_schedules,
                { FeatureTarget.Action(AppAction.OpenSchedules) },
                section = SettingsSection.Tools,
            ),
            FeatureContribution(
                "settings.ask_ai_help",
                FeaturePlacement.Settings,
                R.string.ask_ai_help_title,
                { FeatureTarget.Page(FeaturePage.AskAi) },
                section = SettingsSection.Help,
            ),
            FeatureContribution(
                "settings.about",
                FeaturePlacement.Settings,
                R.string.about_title,
                { FeatureTarget.Page(FeaturePage.About) },
                section = SettingsSection.Help,
            ),
            FeatureContribution(
                "session.transcript_artifacts",
                FeaturePlacement.SessionContent,
                R.string.transcript_artifacts,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenSessionArtifacts(session)) }
                },
            ),
            FeatureContribution(
                "session.workspace_files",
                FeaturePlacement.SessionContent,
                R.string.workspace_files,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenWorkspaceFiles(session)) }
                },
            ),
            FeatureContribution(
                "session.session_export_title",
                FeaturePlacement.SessionContent,
                R.string.session_export_title,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenSessionExport(session)) }
                },
            ),
            FeatureContribution(
                "session.navigation_agents",
                FeaturePlacement.SessionTools,
                R.string.navigation_agents,
                { session -> session?.let { FeatureTarget.Action(AppAction.OpenAgents(session)) } },
            ),
            FeatureContribution(
                "session.skills_title",
                FeaturePlacement.SessionTools,
                R.string.skills_title,
                { session -> session?.let { FeatureTarget.Action(AppAction.OpenSkills(session)) } },
            ),
            FeatureContribution(
                "session.jobs_title",
                FeaturePlacement.SessionTools,
                R.string.jobs_title,
                { session -> session?.let { FeatureTarget.Action(AppAction.OpenJobs(session)) } },
            ),
            FeatureContribution(
                "session.navigation_plugin_panels",
                FeaturePlacement.SessionTools,
                R.string.navigation_plugin_panels,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenPluginSeats(session)) }
                },
            ),
            FeatureContribution(
                "session.navigation_terminal",
                FeaturePlacement.SessionTools,
                R.string.navigation_terminal,
                { session -> FeatureTarget.Action(AppAction.OpenTerminal) },
            ),
            FeatureContribution(
                "session.navigation_schedules",
                FeaturePlacement.SessionTools,
                R.string.navigation_schedules,
                { session -> FeatureTarget.Action(AppAction.OpenSchedules) },
            ),
            FeatureContribution(
                "session.permissions_title",
                FeaturePlacement.SessionManage,
                R.string.permissions_title,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenPermissions(session)) }
                },
            ),
            FeatureContribution(
                "session.directory_from_session",
                FeaturePlacement.SessionManage,
                R.string.directory_from_session,
                { session ->
                    session?.let { FeatureTarget.Action(AppAction.OpenDirectoryPicker(session)) }
                },
            ),
        ),
    )

val builtInComposerActions =
    listOf(
        AppModuleContribution.ComposerAction(
            id = "app:photos",
            surface = "composer.toolbar",
            label = { it.getString(R.string.composer_photos) },
            icon = R.drawable.ic_s_gallery,
            invoke = { AppAction.PickAttachments(it.sessionId, ComposerAttachmentKind.Photos) },
            enabled = { !it.readOnly && !it.sending && !it.syncing },
        ),
        AppModuleContribution.ComposerAction(
            id = "app:capture",
            surface = "composer.toolbar",
            label = { it.getString(R.string.composer_capture) },
            icon = R.drawable.ic_camera,
            invoke = { AppAction.CapturePhoto(it.sessionId) },
            enabled = { !it.readOnly && !it.sending && !it.syncing },
        ),
        AppModuleContribution.ComposerAction(
            id = "app:attachments",
            surface = "composer.toolbar",
            label = { it.getString(R.string.composer_files) },
            icon = R.drawable.ic_s_doc,
            invoke = { AppAction.PickAttachments(it.sessionId) },
            enabled = { !it.readOnly && !it.sending && !it.syncing },
        ),
        AppModuleContribution.ComposerAction(
            id = "app:location",
            surface = "composer.toolbar",
            label = { it.getString(R.string.composer_location) },
            icon = R.drawable.ic_location,
            invoke = { AppAction.InsertLocation(it.sessionId) },
            enabled = { !it.readOnly && !it.sending && !it.syncing },
        ),
        AppModuleContribution.ComposerAction(
            id = "app:media",
            surface = "composer.toolbar",
            label = { it.getString(R.string.composer_media) },
            icon = R.drawable.ic_media,
            invoke = { AppAction.PickAttachments(it.sessionId, ComposerAttachmentKind.Media) },
            enabled = { !it.readOnly && !it.sending && !it.syncing },
        ),
    )

internal fun builtInModule() =
    dev.fughetta.dshcompose.domain.features.HostModule<AppModuleContribution>(
        "app:shell",
        contributions =
            builtInContributions.entries.map { AppModuleContribution.Menu(it) } +
                builtInPages +
                builtInComposerActions +
                builtInMessageRenderers +
                dev.fughetta.dshcompose.ui.commands.builtInCommands +
                dev.fughetta.dshcompose.ui.shell.FeatureCatalog.map {
                    AppModuleContribution.SearchEntry(it)
                },
    )
