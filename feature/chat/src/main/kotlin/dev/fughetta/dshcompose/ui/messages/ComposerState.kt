package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.domain.drafts.DraftsRepository
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.conversation.ChatUiState
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ComposerState(private val repository: DraftsRepository, private val scope: CoroutineScope) {
    private val guard = Any()
    private val touched = mutableSetOf<String>()
    private val retired = mutableSetOf<String>()
    private val mutable = MutableStateFlow(ComposerSnapshot())
    val state = mutable.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.state.collect { saved ->
                synchronized(guard) {
                    var inputs = mutable.value.inputs
                    saved.values.forEach { (id, text) ->
                        if (id !in touched && id !in retired)
                            inputs = inputs.putting(id, ComposerInput(draft = text))
                    }
                    mutable.value = ComposerSnapshot(inputs, saved.storageErrorRevision)
                }
            }
        }
    }

    fun input(sessionId: String): ComposerInput =
        synchronized(guard) {
            mutable.value.inputs[sessionId]
                ?: ComposerInput(
                    draft =
                        if (sessionId in retired) ""
                        else repository.state.value.values[sessionId].orEmpty(),
                )
        }

    fun edit(
        sessionId: String,
        replaceText: Boolean = false,
        transform: (ComposerInput) -> ComposerInput,
    ): ComposerInput =
        synchronized(guard) {
            val previous = input(sessionId)
            if (sessionId in retired) return@synchronized previous
            val next = transform(previous)
            val firstEdit = touched.add(sessionId)
            mutable.value =
                mutable.value.copy(inputs = mutable.value.inputs.putting(sessionId, next))
            if (
                previous.draft != next.draft ||
                    replaceText &&
                        firstEdit &&
                        !repository.state.value.loaded &&
                        next.draft.isEmpty()
            )
                persist(sessionId, next.draft)
            next
        }

    fun promote(from: String, to: String): ComposerInput =
        synchronized(guard) {
            require(from != to)
            val value = input(from)
            touched.add(from)
            touched.add(to)
            mutable.value =
                mutable.value.copy(
                    inputs = mutable.value.inputs.putting(from, ComposerInput()).putting(to, value),
                )
            persist(to, value.draft)
            persist(from, "")
            value
        }

    fun forget(sessionId: String) =
        synchronized(guard) {
            retired.add(sessionId)
            touched.add(sessionId)
            mutable.value = mutable.value.copy(inputs = mutable.value.inputs.removing(sessionId))
            persist(sessionId, "")
        }

    /** Changing hosts drops transient editing state while retaining repository-owned draft text. */
    fun resetTransient() =
        synchronized(guard) {
            touched.clear()
            retired.clear()
            mutable.value =
                mutable.value.copy(
                    inputs =
                        repository.state.value.values
                            .mapValues { ComposerInput(draft = it.value) }
                            .toPersistentMap(),
                )
        }

    fun sent(sessionId: String, text: String, quote: String?) =
        edit(sessionId) { current ->
            val sameText = current.draft == text
            current.copy(
                draft = if (sameText) "" else current.draft,
                quote = if (current.quote == quote) null else current.quote,
                draftCursor = if (sameText) null else current.draftCursor,
                draftSelectionEnd = if (sameText) null else current.draftSelectionEnd,
                draftSpans = if (sameText) persistentListOf() else current.draftSpans,
            )
        }

    private fun persist(sessionId: String, text: String) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (text.isEmpty()) repository.forget(sessionId) else repository.save(sessionId, text)
        }
    }
}

fun ChatUiState.withComposer(input: ComposerInput): ChatUiState =
    if (
        draft == input.draft &&
            quote == input.quote &&
            draftCursor == input.draftCursor &&
            draftSelectionEnd == input.draftSelectionEnd &&
            draftSpans == input.draftSpans
    )
        this
    else
        copy(
            draft = input.draft,
            quote = input.quote,
            draftCursor = input.draftCursor,
            draftSelectionEnd = input.draftSelectionEnd,
            draftSpans = input.draftSpans,
        )
