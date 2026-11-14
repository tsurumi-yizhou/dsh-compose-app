package dev.fughetta.dshcompose.ui.conversation

import dev.fughetta.dshcompose.domain.sessions.SubagentAddress
import dev.fughetta.dshcompose.domain.sessions.SubagentMode
import dev.fughetta.dshcompose.domain.sessions.TranscriptSnapshot

data class ConversationPublication(
    val view: ChatUiState,
    val readOnly: Boolean,
    val home: String,
    val permissionChanged: Boolean,
    val rememberSelection: Boolean,
)

fun TranscriptSnapshot.toConversationPublication(subagent: SubagentAddress?) =
    ConversationPublication(
        view =
            ChatUiState(
                messageRevision = revision,
                historyEpoch = historyEpoch,
                model = model,
                agentPreset = agentPreset,
                messages = presentation.items,
                messageOrder = presentation.order,
                turns = presentation.turns,
                inbox = inbox,
                mediaRevision = presentation.mediaRevision,
                generatedMedia = presentation.generatedMedia,
                generatedMediaPaths = presentation.generatedMediaPaths,
                title = title,
                goal = goal,
                todos = todos,
                origin = origin,
                running = running,
                hasMoreHistory = hasMoreHistory,
                syncing = syncing,
            ),
        readOnly =
            (subagent != null || origin == "subagent") &&
                subagent?.mode != SubagentMode.Continuable,
        home = requireNotNull(generation).home,
        permissionChanged = permissionChanged,
        rememberSelection = summary?.let { it.origin != "subagent" } == true,
    )
