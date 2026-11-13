package dev.fughetta.dshcompose.ui.conversation

import dev.fughetta.dshcompose.domain.transcript.ChatMessageOrder
import dev.fughetta.dshcompose.domain.transcript.TranscriptTurnIndex
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.*

data class ConversationsSnapshot(
    val revision: Long = 0,
    val chats: PersistentMap<String, ChatUiState> = persistentMapOf(),
)

/**
 * Owns conversation presentation; text and selected files are projections of their input owners.
 */
class ConversationsState(
    private val composer: ComposerState,
    private val attachments: ComposerAttachmentsState,
) {

    private val mutable = MutableStateFlow(ConversationsSnapshot())

    val state = mutable.asStateFlow()

    private var foldCompletedTurns = true

    fun update(id: String, transform: (ChatUiState) -> ChatUiState) {

        mutable.update { snapshot ->
            val previous = snapshot.chats[id]

            val chat = previous ?: ChatUiState(foldCompletedTurns = foldCompletedTurns)

            val next = transform(withAttachments(id, chat.withComposer(composer.input(id))))

            if (next === previous) snapshot
            else
                snapshot.copy(
                    revision = snapshot.revision + 1,
                    chats = snapshot.chats.putting(id, next),
                )
        }
    }

    /**
     * Only transcript-owned fields enter presentation; input and pending interactions keep their
     *
     * owners.
     */
    fun publishTranscript(id: String, view: ChatUiState, readOnly: Boolean) {

        update(id) { chat ->
            if (view.messageRevision <= chat.messageRevision) chat
            else
                chat.copy(
                    readOnly = readOnly,
                    foldCompletedTurns = foldCompletedTurns,
                    messageRevision = view.messageRevision,
                    historyEpoch = view.historyEpoch,
                    messages = view.messages,
                    messageOrder = view.messageOrder,
                    turns = view.turns,
                    inbox = view.inbox,
                    mediaRevision = view.mediaRevision,
                    generatedMedia = view.generatedMedia,
                    generatedMediaPaths = view.generatedMediaPaths,
                    title = view.title,
                    origin = view.origin,
                    todos = view.todos,
                    goal = view.goal,
                    model = view.model,
                    agentPreset = view.agentPreset,
                    running = view.running,
                    hasMoreHistory = view.hasMoreHistory,
                    loadingHistory =
                        if (view.syncing || view.historyEpoch != chat.historyEpoch) false
                        else chat.loadingHistory,
                    syncing = view.syncing,
                    error = null,
                )
        }
    }

    /**
     * Keep the input and lightweight metadata while releasing the departed conversation's content.
     */
    fun releaseTranscript(id: String, revision: Long) {

        if (id !in mutable.value.chats) return

        update(id) {
            it.copy(
                messageRevision = revision,
                messages = persistentListOf(),
                messageOrder = ChatMessageOrder.Empty,
                turns = TranscriptTurnIndex.Empty,
                inbox = persistentListOf(),
                mediaRevision = 0,
                generatedMedia = persistentMapOf(),
                generatedMediaPaths = persistentSetOf(),
                goal = null,
                todos = null,
                viewReply = null,
                syncing = true,
                hasMoreHistory = false,
                loadingHistory = false,
            )
        }
    }

    fun updateAll(transform: (String, ChatUiState) -> ChatUiState) {

        mutable.update { snapshot ->
            var rows = snapshot.chats

            for ((id, chat) in snapshot.chats) {

                val next = transform(id, chat)

                if (next !== chat) rows = rows.putting(id, next)
            }

            if (rows === snapshot.chats) snapshot
            else snapshot.copy(revision = snapshot.revision + 1, chats = rows)
        }
    }

    fun syncComposer() = updateAll { id, chat -> chat.withComposer(composer.input(id)) }

    fun syncAttachments() = updateAll(::withAttachments)

    private fun withAttachments(id: String, chat: ChatUiState): ChatUiState {

        val selected =
            attachments.state.value[id].orEmpty().map { file ->
                chat.attachments.firstOrNull { it.id == file.id }
                    ?: ComposerAttachment(file.id, file.name, file.bytes, file.mediaType)
            }

        return if (selected == chat.attachments) chat else chat.copy(attachments = selected)
    }

    fun reconnecting() = updateAll { _, chat ->
        chat.copy(
            syncing = true,
            sending = false,
            viewReply = null,
            loadingHistory = false,
            approvals = emptyList(),
            questions = emptyList(),
            attachments = chat.attachments.map { it.copy(progress = null) },
        )
    }

    fun disconnected() = updateAll { _, chat ->
        chat.copy(syncing = true, approvals = emptyList(), questions = emptyList())
    }

    fun foldCompletedTurns(value: Boolean) {

        foldCompletedTurns = value

        updateAll { _, chat ->
            if (chat.foldCompletedTurns == value) chat else chat.copy(foldCompletedTurns = value)
        }
    }

    fun remove(id: String) {

        mutable.update { snapshot ->
            if (id !in snapshot.chats) snapshot
            else
                snapshot.copy(revision = snapshot.revision + 1, chats = snapshot.chats.removing(id))
        }
    }

    fun reset() {

        mutable.update { snapshot ->
            if (snapshot.chats.isEmpty()) snapshot else ConversationsSnapshot(snapshot.revision + 1)
        }
    }

    fun promoteDraft(sessionId: String) {

        require(sessionId != DRAFT_SESSION_ID)

        mutable.update { snapshot ->
            val draft = snapshot.chats[DRAFT_SESSION_ID] ?: return@update snapshot

            val promoted =
                withAttachments(sessionId, draft.withComposer(composer.input(sessionId)))
                    .copy(sending = false)

            snapshot.copy(
                revision = snapshot.revision + 1,
                chats = snapshot.chats.removing(DRAFT_SESSION_ID).putting(sessionId, promoted),
            )
        }
    }
}
