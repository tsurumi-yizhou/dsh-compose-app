package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.i18n.AppStrings
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createPermissions() = run {
    ownedModel {
        dev.fughetta.dshcompose.permissions.PermissionsViewModel(
            null,
            message = { AppStrings.get(app, it) },
            autoReviewConfirmed = { settings.data.first().autoReviewConfirmed },
            rememberAutoReview = { settings.confirmAutoReview() },
            publish = { permissions -> mutableState.update { it.copy(permissions = permissions) } },
        )
    }
}

internal fun AppViewModel.createSkills() = run {
    ownedModel {
        dev.fughetta.dshcompose.skills.SkillsViewModel(
            null,
            { AppStrings.get(app, it) },
            context = { id ->
                val current = mutableState.value
                val workspaces = current.workspaces
                val chat = conversations.state.value.chats[id]
                dev.fughetta.dshcompose.skills.SkillUseContext(
                    workspaces.rows,
                    workspaces.selectedId,
                    workspaces.connected &&
                        workspaces.preferencesLoaded &&
                        !workspaces.loading &&
                        !workspaces.writing &&
                        workspaces.error == null,
                    chat != null && !chat.sending && !chat.syncing && chat.origin != "subagent",
                )
            },
            publish = { skills -> mutableState.update { it.copy(skills = skills) } },
            insert = { owner, id, skill ->
                var inserted = false
                if (connectionSession?.repositories?.skillsPorts === owner)
                    updateComposer(id) { chat ->
                        if (conversations.state.value.chats[id]?.sending == true) chat
                        else {
                            val result =
                                insertComposerSkill(
                                    chat.draft,
                                    chat.draftCursor ?: chat.draftSelectionEnd,
                                    skill.name,
                                    chat.draftSpans,
                                )
                            inserted = true
                            chat.copy(
                                draft = result.input.text,
                                draftCursor = result.input.end,
                                draftSelectionEnd = result.input.end,
                                draftSpans = result.spans,
                            )
                        }
                    }
                inserted
            },
            openSession = { owner, id ->
                if (connectionSession?.repositories?.skillsPorts === owner) {
                    mutableState.update {
                        it.copy(
                            openSessionId = id,
                            openSessionRevision = it.openSessionRevision + 1,
                        )
                    }
                    openSession(id)
                }
            },
            selectWorkspace = { workspaces.select(it) },
        )
    }
}
