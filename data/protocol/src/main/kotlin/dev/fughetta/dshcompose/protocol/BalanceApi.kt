package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit one-off key check; the caller supplies and owns no persistent credential. */
class ProviderBalanceClient : AutoCloseable {
    private val http =
        HttpClient(CIO) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 8000
                requestTimeoutMillis = 12000
                socketTimeoutMillis = 12000
            }
        }

    suspend fun read(key: ProviderKey): ProviderBalanceResult =
        withContext(Dispatchers.IO) {
            try {
                http
                    .prepareGet("https://api.deepseek.com/user/balance") {
                        header(HttpHeaders.Authorization, "Bearer ${key.text()}")
                        header(HttpHeaders.Accept, "application/json")
                    }
                    .execute { response ->
                        when (response.status.value) {
                            401,
                            403 -> ProviderBalanceResult.InvalidKey
                            200 ->
                                parseProviderBalance(
                                        wireJson.parseToJsonElement(
                                            response.boundedBytes(65536).toString(Charsets.UTF_8),
                                        ),
                                    )
                                    ?.let { ProviderBalanceResult.Ready(it) }
                                    ?: ProviderBalanceResult.Unavailable
                            else -> ProviderBalanceResult.Unavailable
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProviderBalanceResult.Unavailable
            } finally {
                key.close()
            }
        }

    override fun close() {
        http.close()
    }
}

fun parseProviderBalance(raw: JsonElement): ProviderBalance? {
    val root = raw as? JsonObject ?: return null
    val values = (root["balance_infos"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    val value =
        values.firstOrNull { it.string("currency") == "CNY" } ?: values.firstOrNull() ?: return null
    fun amount(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()
    val total = amount("total_balance") ?: return null
    return ProviderBalance(
        value.string("currency") ?: "CNY",
        total,
        amount("granted_balance"),
        amount("topped_up_balance"),
        root.boolean("is_available") != false,
    )
}
