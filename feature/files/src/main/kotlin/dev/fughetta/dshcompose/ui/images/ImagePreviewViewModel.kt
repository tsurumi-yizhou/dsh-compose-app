package dev.fughetta.dshcompose.ui.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.attachments.AttachmentsRepository
import dev.fughetta.dshcompose.domain.attachments.ChatAttachment
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.AppAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ImagePreviewState(
    val name: String?,
    val bytes: ByteArray? = null,
    val error: String? = null,
)

class ImagePreviewViewModel(private val repository: StateFlow<AttachmentsRepository?>) :
    ViewModel() {
    private class Request(val source: AttachmentsRepository) {
        var job: Job? = null
    }

    private val mutable = MutableStateFlow<ImagePreviewState?>(null)
    val uiState = mutable.asStateFlow()
    private var source: AttachmentsRepository? = repository.value
    private var request: Request? = null

    init {
        viewModelScope.launch {
            repository.collect { next ->
                if (source !== next) {
                    source = next
                    dismiss()
                }
            }
        }
    }

    fun handle(action: AppAction): Boolean =
        when (action) {
            is AppAction.OpenImage -> {
                open(action.sessionId, action.attachment)
                true
            }
            AppAction.CloseImage -> {
                dismiss()
                true
            }
            else -> false
        }

    fun open(sessionId: String, attachment: ChatAttachment) {
        val owner = repository.value ?: return
        source = owner
        dismiss()
        val next = Request(owner)
        request = next
        mutable.value = ImagePreviewState(attachment.name)
        next.job = viewModelScope.launch {
            try {
                val bytes = owner.readImage(sessionId, attachment.reference)
                if (current(next)) mutable.value = ImagePreviewState(attachment.name, bytes)
            } catch (error: CancellationException) {
                if (current(next)) dismiss()
                throw error
            } catch (error: Exception) {
                if (current(next))
                    mutable.value = ImagePreviewState(attachment.name, error = error.message)
            }
        }
    }

    override fun onCleared() {
        dismiss()
    }

    private fun current(owner: Request) = request === owner && repository.value === owner.source

    fun dismiss() {
        val previous = request
        request = null
        mutable.value = null
        previous?.job?.cancel()
    }
}
