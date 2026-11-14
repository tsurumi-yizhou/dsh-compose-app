package dev.fughetta.dshcompose.ui.messages

import dev.fughetta.dshcompose.ui.composer.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ComposerInput(
    val draft: String = "",
    val quote: String? = null,
    val draftCursor: Int? = null,
    val draftSelectionEnd: Int? = null,
    val draftSpans: PersistentList<ReferenceSpan> = persistentListOf(),
)

data class ComposerSnapshot(
    val inputs: PersistentMap<String, ComposerInput> = persistentMapOf(),
    val storageErrorRevision: Long = 0,
)

/** Owns editor state independently of transcript and application-shell updates. */
