package dev.fughetta.dshcompose.ui.models

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ModelPickerState(
    val sessionId: String,
    val catalog: ModelCatalog? = null,
    val candidate: ModelSelection? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)
