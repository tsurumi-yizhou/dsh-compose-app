package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.skills.SkillInfo
import dev.fughetta.dshcompose.domain.workspaces.Workspace

/** Workspace-owned skills must keep that directory when opening a fresh conversation. */
fun skillWorkspaceSwitch(
    skill: SkillInfo,
    sessionId: String,
    workspaces: List<Workspace>,
    selected: String?,
): String? {
    val path = skill.path?.takeIf { it.isNotEmpty() } ?: return null
    val source = workspaces.firstOrNull { sessionId in it.sessionIds } ?: return null
    if (source.id == selected || source.path.isEmpty()) return null
    val root = if (source.path.endsWith('/')) source.path else "${source.path}/"
    return source.id.takeIf { path.startsWith(root) }
}
