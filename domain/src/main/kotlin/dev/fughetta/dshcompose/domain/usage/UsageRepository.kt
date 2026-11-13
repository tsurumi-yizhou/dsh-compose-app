package dev.fughetta.dshcompose.domain.usage

import dev.fughetta.dshcompose.domain.account.DeepSeekAccountBalance
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.providers.ProviderKey
import kotlinx.coroutines.flow.StateFlow

/** Selected-conversation usage and balance reads, including account/key reads while offline. */
interface UsageRepository : AutoCloseable {
    val generation: ConnectionGeneration?
    val state: StateFlow<UsageReading>

    suspend fun projections(sessionId: String): ToolPayload?

    fun connect(owner: UsageRepository?, hostKey: String?, sessionId: String?)

    fun sessionUpdate(update: UsageChange)

    fun account(value: DeepSeekAccountBalance?)

    fun observePanel(active: Boolean)

    fun refresh(immediate: Boolean = false)

    fun checkKey(key: ProviderKey)
}
