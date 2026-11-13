package dev.fughetta.dshcompose.domain.hosts

import dev.fughetta.dshcompose.domain.notify.NotificationsRepository
import dev.fughetta.dshcompose.domain.permissions.PermissionsRepository
import dev.fughetta.dshcompose.domain.plugins.PluginUiRepository
import dev.fughetta.dshcompose.domain.plugins.PluginsRepository
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.settings.*
import dev.fughetta.dshcompose.domain.skills.*
import dev.fughetta.dshcompose.domain.terminal.TerminalRepository
import dev.fughetta.dshcompose.domain.uiconfig.UiConfigPorts
import dev.fughetta.dshcompose.domain.usage.UsageRepository
import dev.fughetta.dshcompose.domain.workspaces.*

/** Repository bindings published together before the host's ready state. */
class HostRepositories(
    val generation: ConnectionGeneration,
    val sessions: SessionsRepository,
    val files: WorkspaceFilesRepository,
    val workspaces: WorkspacesRepository,
    val plugins: PluginsRepository,
    val usage: UsageRepository,
    val permissions: PermissionsRepository,
    val terminals: TerminalRepository,
    val skills: SkillsRepository,
    val notifications: NotificationsRepository,
    val pluginUi: PluginUiRepository?,
    val directoryPicker: DirectoryPickerPorts,
    val engineSettings: EngineSettingsPorts,
    val skillsPorts: SkillsPorts,
    val uiConfig: UiConfigPorts,
)
