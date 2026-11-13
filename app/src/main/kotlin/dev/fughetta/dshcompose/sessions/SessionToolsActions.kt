package dev.fughetta.dshcompose.sessions

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.handlePermissionsCommandsSkillsJobs(action: AppAction): Boolean {
    when (action) {
        is AppAction.OpenPermissions -> {
            permissions.open(action.sessionId)
            return true
        }
        AppAction.ClosePermissions -> {
            permissions.closeDialog()
            return true
        }
        is AppAction.ObservePermissions -> {
            permissions.observe(action.observing)
            return true
        }
        AppAction.RefreshPermissions -> {
            permissions.refresh()
            return true
        }
        is AppAction.SelectPermission -> {
            permissions.choose(action.value, action.target)
            return true
        }
        AppAction.ConfirmPermission -> {
            permissions.confirm()
            return true
        }
        AppAction.CancelPermission -> {
            permissions.cancel()
            return true
        }
        is AppAction.RememberAutoReview -> {
            permissions.remember(action.remember)
            return true
        }
        is AppAction.PickCommand -> {
            pickCommand(action.sessionId, action.name)
            return true
        }
        AppAction.RefreshCommands -> {
            commands.invalidate()
            return true
        }
        is AppAction.OpenSkills -> {
            skills.open(action.sessionId)
            return true
        }
        AppAction.CloseSkills -> {
            skills.closeDialog()
            return true
        }
        is AppAction.ObserveSkills -> {
            skills.observe(action.observing)
            return true
        }
        AppAction.RefreshSkills -> {
            skills.refresh()
            return true
        }
        is AppAction.QuerySkills -> {
            skills.query(action.text)
            return true
        }
        is AppAction.ViewSkill -> {
            skills.view(action.name)
            return true
        }
        AppAction.CloseSkillDetail -> {
            skills.closeDetail()
            return true
        }
        is AppAction.UseSkill -> {
            skills.use(action.fresh)
            return true
        }
        AppAction.OpenSkillCreatedSession -> {
            skills.openCreated()
            return true
        }
        else -> return false
    }
}
