package dev.fughetta.dshcompose.domain.providers

import kotlinx.coroutines.flow.StateFlow

/** Model catalog and acknowledged selections owned by one connection generation. */
interface SessionModelsRepository {
    val changes: StateFlow<Long>

    suspend fun defaults(): ModelDefaults?

    suspend fun setDefaults(previous: ModelDefaults, selection: ModelSelection, remember: Boolean)

    suspend fun catalog(): ModelCatalog

    suspend fun select(sessionId: String, selection: ModelSelection): ModelSelectionResult

    fun invalidate()
}

class ModelOperationException(val code: String, cause: Throwable? = null) : Exception(code, cause)
