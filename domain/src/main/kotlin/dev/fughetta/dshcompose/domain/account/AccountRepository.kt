package dev.fughetta.dshcompose.domain.account

import kotlinx.coroutines.flow.Flow

/** Signed-in state, sign-in flow and balance of the DeepSeek account on one host. */
interface AccountRepository {
    /** Opaque link token: equal only while the same host generation is connected. */
    val generation: Any?

    fun updates(current: () -> Boolean, onFailure: (Exception) -> Unit): Flow<DeepSeekAccountView>

    suspend fun beginSignIn(accepted: () -> Boolean, canCancel: () -> Boolean): DeepSeekAccountView?

    suspend fun state(): DeepSeekAccountView

    fun watch(): Flow<DeepSeekAccountView>

    suspend fun signIn(): DeepSeekAccountView

    suspend fun cancel(id: String): DeepSeekAccountView

    suspend fun signOut(): DeepSeekAccountView

    suspend fun profile(): DeepSeekAccountProfile?

    suspend fun balance(): DeepSeekAccountBalance?

    suspend fun runningTasks(): Boolean

    suspend fun initializeDefaultModel()
}
