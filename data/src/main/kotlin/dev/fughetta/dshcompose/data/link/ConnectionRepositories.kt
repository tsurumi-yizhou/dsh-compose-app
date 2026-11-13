package dev.fughetta.dshcompose.data.link

import dev.fughetta.dshcompose.data.notify.EngineNotificationsRepository
import dev.fughetta.dshcompose.data.permissions.EnginePermissionsRepository
import dev.fughetta.dshcompose.data.plugins.EnginePluginUiRepository
import dev.fughetta.dshcompose.data.plugins.EnginePluginsRepository
import dev.fughetta.dshcompose.data.skills.EngineSkillsRepository
import dev.fughetta.dshcompose.data.terminal.EngineTerminalRepository
import dev.fughetta.dshcompose.data.usage.EngineUsageRepository
import dev.fughetta.dshcompose.data.workspace.EngineWorkspaceFilesRepository
import dev.fughetta.dshcompose.data.workspace.EngineWorkspacesRepository
import dev.fughetta.dshcompose.domain.hosts.HostRepositories
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.settings.EngineSettingsPorts
import dev.fughetta.dshcompose.domain.settings.SettingsRepository
import dev.fughetta.dshcompose.domain.skills.SkillsPorts
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigPorts
import dev.fughetta.dshcompose.domain.workspaces.DirectoryPickerPorts
import dev.fughetta.dshcompose.protocol.ProtocolClient

/** Owns screen repositories and native resources for one ready connection generation. */
class ConnectionRepositories
internal constructor(
    client: ProtocolClient,
    val sessions: SessionsRepository,
    settings: SettingsRepository,
) : AutoCloseable {
    val generation = requireNotNull(client.mux.state.value.generation)
    @Volatile private var active = true
    val files = EngineWorkspaceFilesRepository(client) { active }
    val workspaces = EngineWorkspacesRepository(client) { active }
    val plugins = EnginePluginsRepository(client) { active }
    val usage = EngineUsageRepository(client) { active }
    val permissions = EnginePermissionsRepository(client) { active }
    val terminals = EngineTerminalRepository(client) { active }
    val skills = EngineSkillsRepository(client) { active }
    val notifications = EngineNotificationsRepository(client) { active }
    private val clientSurfaces = EnginePluginUiRepository(client) { active }
    val pluginUi = clientSurfaces.takeIf { it.supportsNativePlugins }
    val directoryPicker = DirectoryPickerPorts(files, workspaces, sessions)
    val engineSettings = EngineSettingsPorts(settings, plugins)
    val skillsPorts = SkillsPorts(skills, sessions)
    val uiConfig = UiConfigPorts(sessions, skills, files)
    val view =
        HostRepositories(
            generation,
            sessions,
            files,
            workspaces,
            plugins,
            usage,
            permissions,
            terminals,
            skills,
            notifications,
            pluginUi,
            directoryPicker,
            engineSettings,
            skillsPorts,
            uiConfig,
        )

    override fun close() {
        if (!active) return
        active = false
        usage.close()
        terminals.close()
        plugins.close()
        clientSurfaces.close()
    }
}
