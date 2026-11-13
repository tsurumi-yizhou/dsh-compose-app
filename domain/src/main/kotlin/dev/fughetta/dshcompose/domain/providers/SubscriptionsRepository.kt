package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.flow.StateFlow

/** One connection generation; retired instances never serve a replacement connection. */
interface SubscriptionsRepository {
    val changes: StateFlow<Long>

    suspend fun statuses(): PersistentList<SubscriptionStatus>

    suspend fun begin(subscription: Subscription): SubscriptionLogin

    suspend fun signOut(subscription: Subscription)

    fun invalidate()
}

/** A connection-owned login attempt. Closing releases its pending engine authorization. */
class SubscriptionLogin(
    val poll: suspend () -> LoginStep,
    val answer: suspend (ProviderKey) -> Unit,
    val cancel: suspend () -> Unit,
    private val release: () -> Unit,
) : AutoCloseable {
    override fun close() = release()
}

class SubscriptionOperationException(val code: String, cause: Throwable? = null) :
    Exception(code, cause)
