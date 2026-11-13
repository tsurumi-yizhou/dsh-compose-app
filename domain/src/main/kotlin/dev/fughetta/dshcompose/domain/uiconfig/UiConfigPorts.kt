package dev.fughetta.dshcompose.domain.uiconfig

import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import dev.fughetta.dshcompose.domain.skills.SkillsRepository
import dev.fughetta.dshcompose.domain.workspaces.WorkspaceFilesRepository

/** What the remote UI-config follower needs from one host. */
data class UiConfigPorts(
    val sessions: SessionsRepository,
    val skills: SkillsRepository,
    val files: WorkspaceFilesRepository,
)
