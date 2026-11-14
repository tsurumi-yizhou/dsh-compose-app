package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.domain.messages.UserQuestionRequest
import dev.fughetta.dshcompose.domain.permissions.PermissionSnapshot
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import dev.fughetta.dshcompose.domain.skills.SkillInfo
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.goals.*
import dev.fughetta.dshcompose.ui.jobs.*
import dev.fughetta.dshcompose.ui.presets.*
import dev.fughetta.dshcompose.ui.providers.*
import dev.fughetta.dshcompose.ui.schedules.*
import kotlinx.collections.immutable.*

data class SkillsUiState(
    val revision: Long = 0,
    val sessionId: String,
    val skills: PersistentList<dev.fughetta.dshcompose.domain.skills.SkillInfo> =
        persistentListOf(),
    val query: String = "",
    val selected: dev.fughetta.dshcompose.domain.skills.SkillInfo? = null,
    val loading: Boolean = true,
    val filtering: Boolean = false,
    val busy: Boolean = false,
    val canUseHere: Boolean = false,
    val creationUncertain: Boolean = false,
    val createdSessionId: String? = null,
    val error: String? = null,
)

enum class PermissionTarget {
    Current,
    Default,
}

data class PermissionChange(
    val value: String,
    val target: PermissionTarget,
    val revision: Long?,
    val remember: Boolean = true,
)

data class PermissionsUiState(
    val sessionId: String?,
    val snapshot: dev.fughetta.dshcompose.domain.permissions.PermissionSnapshot? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val stale: Boolean = false,
    val pending: PermissionChange? = null,
    val error: String? = null,
    val notice: String? = null,
)

data class ComposerAttachment(
    val id: String,
    val name: String,
    val bytes: Long,
    val mediaType: String,
    val progress: Float? = null,
)

data class ApprovalUiState(
    val eventId: String,
    val toolName: String,
    val reason: String?,
    val displayReason: Map<String, String>,
    val connection: Long,
    val generation: Long,
    val responding: Boolean = false,
)

data class QuestionUiState(
    val request: UserQuestionRequest,
    val connection: Long,
    val generation: Long,
    val answers: Map<String, QuestionAnswer> = emptyMap(),
    val responding: Boolean = false,
    val remainingMs: Long? = null,
)
