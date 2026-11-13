package dev.fughetta.dshcompose.personal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.sharing.ShareFormatException
import dev.fughetta.dshcompose.sharing.ShareGuestFiles
import dev.fughetta.dshcompose.ui.personal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 个人偏好: the user's own AGENTS.md in the local guest; the engine reads it on the next turn. */
class ReplyPrefsViewModel(context: Context) : ViewModel() {
    private val scope
        get() = viewModelScope

    private val guest = ShareGuestFiles(context)
    private val mutable = MutableStateFlow(ReplyPrefsState())
    val state: StateFlow<ReplyPrefsState> = mutable.asStateFlow()
    private var job: Job? = null

    fun action(action: ReplyPrefsAction) {
        when (action) {
            ReplyPrefsAction.Load -> load()
            is ReplyPrefsAction.Save -> save(action.text)
        }
    }

    private fun load() {
        if (mutable.value.saving) return
        job?.cancel()
        job = scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { if (!guest.available) null else guest.importedAgents().orEmpty() }
                }
            mutable.update { current ->
                result.fold(
                    { text ->
                        current.copy(
                            available = text != null,
                            loaded = true,
                            text = text.orEmpty(),
                            error = null,
                        )
                    },
                    { current.copy(loaded = true, error = code(it)) },
                )
            }
        }
    }

    private fun save(text: String) {
        if (mutable.value.saving || !mutable.value.available) return
        if (text.length > ReplyPrefsState.MaxChars) {
            mutable.update { it.copy(error = "share/too-large") }
            return
        }
        mutable.update { it.copy(saving = true, error = null) }
        job?.cancel()
        job = scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (text.isBlank()) guest.removeAgents() else guest.replaceAgents(text)
                    }
                }
            mutable.update { current ->
                result.fold(
                    {
                        current.copy(
                            saving = false,
                            text = if (text.isBlank()) "" else text,
                            savedRevision = current.savedRevision + 1,
                        )
                    },
                    { current.copy(saving = false, error = code(it)) },
                )
            }
        }
    }

    private fun code(error: Throwable) = (error as? ShareFormatException)?.code ?: "REPLY_PREFS_IO"
}
