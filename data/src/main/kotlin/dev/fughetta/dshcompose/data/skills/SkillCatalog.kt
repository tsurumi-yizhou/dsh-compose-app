package dev.fughetta.dshcompose.data.skills

import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.skills.*
import dev.fughetta.dshcompose.domain.workspaces.Workspace
import dev.fughetta.dshcompose.protocol.skillWorkspaceSwitch
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

suspend fun createSkillSession(
    owner: SkillsPorts,
    skill: SkillInfo,
    source: String,
    workspaces: List<Workspace>,
    selectedWorkspace: String?,
): SkillSessionCreation {
    val switch = skillWorkspaceSwitch(skill, source, workspaces, selectedWorkspace)
    val id = withTimeout(20000) { owner.sessions.create(workspaceId = switch ?: selectedWorkspace) }
    return SkillSessionCreation(id, switch)
}
