package dev.fughetta.dshcompose.data.plugins

import android.util.Log
import dev.fughetta.dshcompose.domain.plugins.*
import dev.fughetta.dshcompose.protocol.PluginMarketClient
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

internal class PluginCatalog : AutoCloseable {
    private val market = lazy { PluginMarketClient() }

    suspend fun inventory(client: PluginsRepository): PluginInventory =
        withTimeout(20000) {
            coroutineScope {
                val bundles = async { client.bundles() }
                val entries = async { client.plugins() }
                val cordis = async {
                    optionalInventory {
                        if (client.canReadCordis) client.cordis() else persistentListOf()
                    }
                }
                val configurable = async {
                    optionalInventory {
                        if (client.canReadCompletePluginConfig)
                            client.config().filter { it.configurable }.map { it.entryId }.toSet()
                        else emptySet()
                    }
                }
                val hostBundles = bundles.await()
                val hostEntries = entries.await()
                val runtime = cordis.await()
                val configuration = configurable.await()
                PluginInventory(
                    hostBundles,
                    hostEntries,
                    runtime.getOrDefault(persistentListOf()),
                    configuration.getOrDefault(emptySet()),
                    cordisUnavailable = runtime.isFailure,
                    configUnavailable = configuration.isFailure,
                )
            }
        }

    private suspend fun <T> optionalInventory(read: suspend () -> T): Result<T> =
        try {
            Result.success(withTimeout(10000) { read() })
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    suspend fun market(): Result<PluginMarketIndex> {
        var last: Throwable? = null
        repeat(2) {
            try {
                return Result.success(
                    market.value.read("https://api.dshfind.com/market/dsh-index.json"),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(
                    "PluginMarket",
                    "read attempt failed: ${error::class.simpleName}: ${error.message}",
                )
                last = error
            }
        }
        return Result.failure(last ?: IllegalStateException("plugin market unavailable"))
    }

    override fun close() {
        if (market.isInitialized()) market.value.close()
    }
}
