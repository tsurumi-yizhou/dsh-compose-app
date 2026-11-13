package dev.fughetta.dshcompose.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns the selected conversation, pagination intent and message menu together. */
class ConversationViewModel(
    private val conversations: ConversationsState,
    parent: CoroutineScope? = null,
    private val onPublished: (String, ConversationPublication, ChatUiState?) -> Unit = { _, _, _ ->
    },
) : ViewModel() {
    private val scope = parent ?: viewModelScope

    private class Binding(val id: String, val repository: ConversationRepository) {
        var job: Job? = null
        private var closed = false

        fun close() {
            if (closed) return
            closed = true
            repository.close()
        }
    }

    private var binding: Binding? = null
    val current: ConversationRepository?
        get() = binding?.repository

    fun isActive(id: String) = binding?.let { it.id == id && it.job?.isActive == true } == true

    fun bind(
        id: String,
        repository: ConversationRepository,
        updates: Flow<ConversationPublication>,
    ) {
        clear()
        val next = Binding(id, repository)
        binding = next
        bindHistory(id, repository)
        bindMessages(id, repository)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    updates.collect { publication ->
                        if (binding !== next) return@collect
                        val previous = conversations.state.value.chats[id]
                        if (
                            previous != null &&
                                previous.messageRevision >= publication.view.messageRevision
                        )
                            return@collect
                        conversations.publishTranscript(id, publication.view, publication.readOnly)
                        onPublished(id, publication, previous)
                        // The source folds every event; only UI publications are paced during
                        // bursts.
                        delay(32)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (binding === next)
                        conversations.update(id) { it.copy(syncing = true, error = error.message) }
                }
            }
        next.job = job
        job.invokeOnCompletion { release(next) }
        job.start()
    }

    fun clear() {
        val previous = binding ?: return
        binding = null
        bindHistory(null, null)
        bindMessages(null, null)
        previous.job?.cancel()
        previous.close()
    }

    private fun release(owner: Binding) {
        if (binding === owner) {
            binding = null
            bindHistory(null, null)
            bindMessages(null, null)
        }
        owner.close()
    }

    private class HistoryBinding(val id: String, val repository: ConversationRepository) {
        var epoch = repository.historyEpoch.value
        var loadHistory: Job? = null
    }

    private var historyBinding: HistoryBinding? = null
    private var historyObserver: Job? = null

    fun bindHistory(id: String?, repository: ConversationRepository?) {
        val previous = historyBinding
        historyBinding = null
        historyObserver?.cancel()
        previous?.loadHistory?.cancel()
        previous?.let { historyLoading(it.id, false) }
        if (id == null || repository == null) return
        val next = HistoryBinding(id, repository)
        historyBinding = next
        historyObserver = scope.launch {
            repository.historyEpoch.collect { syncHistoryEpoch(next) }
        }
    }

    private fun syncHistoryEpoch(owner: HistoryBinding) {
        if (historyBinding !== owner) return
        val epoch = owner.repository.historyEpoch.value
        if (owner.epoch == epoch) return
        owner.loadHistory?.cancel()
        owner.epoch = epoch
        historyLoading(owner.id, false)
    }

    fun loadHistory(id: String) {
        val owner = historyBinding?.takeIf { it.id == id } ?: return
        syncHistoryEpoch(owner)
        if (owner.loadHistory?.isActive == true || id !in conversations.state.value.chats) return
        val epoch = owner.epoch
        historyLoading(id, true)
        owner.loadHistory = scope.launch {
            try {
                owner.repository.loadHistory()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionOperationException) {
                if (historyBinding === owner && owner.repository.historyEpoch.value == epoch)
                    conversations.update(id) { it.copy(error = error.code) }
            } finally {
                if (historyBinding === owner && owner.repository.historyEpoch.value == epoch)
                    historyLoading(id, false)
            }
        }
    }

    private fun historyLoading(id: String, value: Boolean) {
        if (id in conversations.state.value.chats)
            conversations.update(id) {
                if (it.loadingHistory == value) it else it.copy(loadingHistory = value)
            }
    }

    private class MessageBinding(val id: String, val repository: ConversationRepository) {
        var epoch = repository.historyEpoch.value
    }

    private class MessageWork(val owner: MessageBinding) {
        var job: Job? = null
    }

    private val messageState = MutableStateFlow<MessageMenuState?>(null)
    val messageMenu = messageState.asStateFlow()
    private var messageBinding: MessageBinding? = null
    private var messageWork: MessageWork? = null
    private var messageObserver: Job? = null

    fun bindMessages(id: String?, repository: ConversationRepository?) {
        messageBinding = null
        messageObserver?.cancel()
        resetMessage()
        if (id == null || repository == null) return
        val owner = MessageBinding(id, repository)
        messageBinding = owner
        messageObserver = scope.launch {
            repository.historyEpoch.collect { epoch ->
                if (messageBinding === owner && owner.epoch != epoch) {
                    owner.epoch = epoch
                    resetMessage()
                }
            }
        }
    }

    fun resetMessage() {
        val previous = messageWork
        messageWork = null
        messageState.value = null
        previous?.job?.cancel()
    }

    fun dismissMessage() {
        if (messageState.value?.busy != true) resetMessage()
    }

    private fun currentMessage(request: MessageWork) =
        messageBinding === request.owner &&
            messageWork === request &&
            request.owner.epoch == request.owner.repository.historyEpoch.value

    fun openMessage(id: String, key: String) {
        val owner = messageBinding?.takeIf { it.id == id } ?: return
        val chat = conversations.state.value.chats[id] ?: return
        if (owner.epoch != owner.repository.historyEpoch.value || chat.historyEpoch != owner.epoch)
            return
        val row = chat.messages.firstOrNull { it.key == key } ?: return
        resetMessage()
        val request = MessageWork(owner)
        messageWork = request
        val menu =
            MessageMenuState(
                id,
                row,
                quoteable = !chat.readOnly,
                editable =
                    row.role == ChatRole.User &&
                        row.seq != null &&
                        !chat.readOnly &&
                        chat.origin != "subagent",
            )
        request.job = scope.launch {
            try {
                val seq = row.seq
                val cut =
                    if (
                        row.role == ChatRole.Assistant &&
                            seq != null &&
                            !row.streaming &&
                            !chat.readOnly &&
                            chat.origin != "subagent"
                    )
                        owner.repository.branchPointAfter(seq)
                    else null
                if (!currentMessage(request)) return@launch
                val latest = conversations.state.value.chats[id] ?: return@launch
                val position = latest.messages.indexOfFirst { it.key == key }
                if (position < 0) {
                    resetMessage()
                    return@launch
                }
                val prompt =
                    if (
                        cut != null &&
                            !latest.running &&
                            !latest.sending &&
                            latest.messages.lastOrNull { it.role != ChatRole.Notice }?.key ==
                                row.key
                    )
                        latest.messages
                            .subList(0, position)
                            .asReversed()
                            .firstOrNull { it.role == ChatRole.User }
                            ?.takeIf { item ->
                                item.attachments.all { it.kind == "image" } &&
                                    (item.text.isNotBlank() || item.attachments.isNotEmpty())
                            }
                    else null
                messageState.value =
                    menu.copy(branchCut = cut, regeneratePrompt = prompt, busy = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentMessage(request))
                    messageState.value = menu.copy(busy = false, error = error.message)
            }
        }
    }

    fun performMessage(operation: MessageOperation, action: suspend MessageAction.() -> Unit) {
        val owner = messageBinding ?: return
        val menu = messageState.value?.takeIf { !it.busy && it.sessionId == owner.id } ?: return
        if (
            operation == MessageOperation.Quote && !menu.quoteable ||
                operation == MessageOperation.Branch && menu.branchCut == null ||
                operation == MessageOperation.Edit && !menu.editable ||
                operation == MessageOperation.Regenerate && menu.regeneratePrompt == null
        )
            return
        messageWork?.job?.cancel()
        val request = MessageWork(owner)
        messageWork = request
        messageState.value = menu.copy(busy = true, error = null)
        fun complete() {
            if (currentMessage(request)) {
                messageWork = null
                messageState.value = null
            }
        }
        request.job = scope.launch {
            try {
                MessageAction(menu, { currentMessage(request) }, ::complete).action()
                complete()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentMessage(request))
                    messageState.value =
                        menu.copy(
                            busy = false,
                            error =
                                when (operation) {
                                    MessageOperation.Copy -> "message/copy-failed"
                                    MessageOperation.Share -> "message/share-failed"
                                    else -> error.message
                                },
                        )
            }
        }
    }

    override fun onCleared() {
        clear()
        bindHistory(null, null)
        bindMessages(null, null)
    }
}
