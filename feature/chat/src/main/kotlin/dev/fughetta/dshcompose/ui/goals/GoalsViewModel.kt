package dev.fughetta.dshcompose.ui.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GoalsViewModel(private val repository: GoalsRepository) : ViewModel() {
    private val mutable = MutableStateFlow<GoalUiState?>(null)
    val uiState = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect { next ->
                mutable.update { before ->
                    when {
                        next == null -> null
                        before?.sessionId != next.sessionId || before.data.owner != next.owner ->
                            GoalUiState(next)
                        else -> {
                            val changed = before.data.mutationRevision != next.mutationRevision
                            before.copy(
                                data = next,
                                dialog = before.dialog && next.visible,
                                editor = before.editor.takeUnless { changed },
                                clearing = before.clearing.takeUnless { changed },
                                dismissedError =
                                    before.dismissedError.takeIf {
                                        before.data.error == next.error
                                    },
                            )
                        }
                    }
                }
            }
        }
    }

    fun handle(action: AppAction): Boolean {
        when (action) {
            is AppAction.ObserveGoal -> repository.observe(action.sessionId, action.observing)
            AppAction.ShowGoal -> update { if (it.visible) it.copy(dialog = true) else it }
            AppAction.CloseGoal ->
                update { it.copy(dialog = false, editor = null, clearing = null) }
            AppAction.RefreshGoal -> repository.refresh()
            AppAction.EditGoal ->
                update { state ->
                    val goal = state.projection?.goal
                    if (goal != null && !state.busy && state.ready)
                        state.copy(
                            editor = GoalEditorState(goal.ref, goal.objective, goal.objective),
                            dismissedError = state.data.error,
                        )
                    else state
                }
            is AppAction.ChangeGoalObjective ->
                update { it.copy(editor = it.editor?.copy(text = action.text)) }
            AppAction.CancelGoalEditor -> update { it.copy(editor = null) }
            AppAction.SaveGoal -> {
                val view = uiState.value
                val editor = view?.editor
                if (
                    editor != null &&
                        editor.text.trim().isNotEmpty() &&
                        editor.text.trim() != editor.initial.trim()
                )
                    viewModelScope.launch {
                        repository.edit(view.data.owner, editor.ref, editor.text.trim())
                    }
            }
            is AppAction.ChangeGoal -> {
                val view = uiState.value
                if (
                    view != null &&
                        action.action != GoalAction.Clear &&
                        (action.action != GoalAction.Pause || view.canPause) &&
                        (action.action != GoalAction.Resume || view.canResume)
                )
                    view.projection?.goal?.ref?.let { ref ->
                        viewModelScope.launch {
                            repository.change(view.data.owner, ref, action.action)
                        }
                    }
            }
            AppAction.AskClearGoal ->
                update { state ->
                    val goal = state.projection?.goal
                    if (goal != null && !state.busy && state.ready)
                        state.copy(
                            clearing = GoalClearState(goal.ref, goal.objective),
                            dismissedError = state.data.error,
                        )
                    else state
                }
            AppAction.CancelClearGoal -> update { if (it.busy) it else it.copy(clearing = null) }
            AppAction.ConfirmClearGoal -> {
                val view = uiState.value
                val clear = view?.clearing
                if (clear != null && !view.busy) {
                    update { it.copy(clearing = null) }
                    viewModelScope.launch { repository.clear(view.data.owner, clear.ref) }
                }
            }
            else -> return false
        }
        return true
    }

    private fun update(change: (GoalUiState) -> GoalUiState) {
        mutable.update { it?.let(change) }
    }
}
