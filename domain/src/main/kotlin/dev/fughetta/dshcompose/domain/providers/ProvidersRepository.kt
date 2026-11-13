package dev.fughetta.dshcompose.domain.providers

import kotlinx.collections.immutable.*
import kotlinx.coroutines.flow.StateFlow

/** One connection generation; retired instances never serve a replacement connection. */
interface ProvidersRepository {
    val changes: StateFlow<Long>

    val snapshot: StateFlow<ProviderSnapshot?>

    suspend fun refresh(): ProviderSnapshot

    suspend fun setCredential(ref: String, key: ProviderKey?)

    suspend fun apply(change: ProviderMutation): ProviderSettings

    suspend fun catalog(): ModelCatalog

    suspend fun discover(route: String): PersistentList<DiscoveredModel>

    suspend fun discover(
        baseUrl: String,
        api: ProviderApi,
        key: ProviderKey?,
    ): PersistentList<DiscoveredModel>

    fun invalidate()
}

data class ProviderSnapshot(
    val settings: ProviderSettings?,
    val accounts: PersistentList<ProviderAccount>,
    val credentials: PersistentMap<String, CredentialStatus>,
)

sealed interface ProviderMutation {
    data class Create(
        val route: String,
        val name: String,
        val api: ProviderApi,
        val baseUrl: String,
        val credentialRef: String?,
        val models: List<ProviderModelSpec>,
        val revision: Long,
    ) : ProviderMutation

    data class Update(
        val previous: ProviderProfile,
        val name: String,
        val credentialRef: String?,
        val api: ProviderApi?,
        val baseUrl: String?,
        val models: List<ProviderModelSpec>?,
        val overrides: Map<String, ProviderModelOverride>?,
        val revision: Long,
    ) : ProviderMutation

    data class Remove(val route: String, val revision: Long) : ProviderMutation
}

class ProviderOperationException(val code: String, cause: Throwable? = null) :
    Exception(code, cause)
