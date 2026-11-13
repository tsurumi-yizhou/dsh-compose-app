package dev.fughetta.dshcompose.data.account

import dev.fughetta.dshcompose.data.asOperationFailure
import dev.fughetta.dshcompose.data.domainOperation
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.account.*
import dev.fughetta.dshcompose.protocol.*
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.*

class EngineAccount(
    private val client: ProtocolClient,
    val callbackOrigin: String,
    private val appVersion: String,
    private val locale: () -> String,
) : AccountRepository {
    private fun metadata() = buildJsonObject {
        put("version", appVersion)
        put("locale", locale())
        put("timezoneOffsetSeconds", ZonedDateTime.now().offset.totalSeconds)
    }

    private fun args() = buildJsonObject { put("client", metadata()) }

    override val generation = requireNotNull(client.mux.state.value.generation)

    private fun requireCurrent() {
        if (client.mux.state.value.generation != generation) throw ConnectionClosedException()
    }

    private suspend fun call(
        endpoint: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): JsonElement {
        requireCurrent()
        return domainOperation { client.connection.call(endpoint, args).also { requireCurrent() } }
    }

    override fun updates(
        current: () -> Boolean,
        onFailure: (Exception) -> Unit,
    ): Flow<DeepSeekAccountView> = accountUpdates(this, current, onFailure)

    override suspend fun beginSignIn(
        accepted: () -> Boolean,
        canCancel: () -> Boolean,
    ): DeepSeekAccountView? = beginAccountSignIn(this, accepted, canCancel)

    override suspend fun state(): DeepSeekAccountView = parseAccountView(call("account/getState"))

    override fun watch(): Flow<DeepSeekAccountView> =
        client.mux
            .stream("account/watch", JsonObject(emptyMap()))
            .onStart { requireCurrent() }
            .mapNotNull {
                requireCurrent()
                if (it is MuxEvent.Item) parseAccountView(it.value) else null
            }
            .catch { throw it.asOperationFailure() }

    override suspend fun signIn(): DeepSeekAccountView =
        parseAccountView(
            call(
                "account/startSignIn",
                buildJsonObject {
                    put("client", metadata())
                    put("callbackOrigin", callbackOrigin)
                    put("loginSource", "web")
                },
            ),
        )

    override suspend fun cancel(id: String): DeepSeekAccountView =
        parseAccountView(call("account/cancelSignIn", buildJsonObject { put("attemptId", id) }))

    override suspend fun signOut(): DeepSeekAccountView =
        parseAccountView(call("account/signOut", args()))

    override suspend fun profile(): DeepSeekAccountProfile? {
        val raw = call("account/getProfile", args())
        if (raw == JsonNull) return null
        val result = raw.objectValue()
        if (result.string("status") != "ready") throw OperationFailure("account/profile-failed")
        val value =
            result["value"]?.objectValue() ?: throw HostProtocolException("Missing account profile")
        return DeepSeekAccountProfile(
            value.string("id"),
            value.string("name"),
            value.string("contact"),
            value.string("avatarUrl"),
        )
    }

    override suspend fun balance(): DeepSeekAccountBalance? {
        val raw = call("account/getBalance", args())
        if (raw == JsonNull) return null
        val result = raw.objectValue()
        if (result.string("status") != "ready") throw OperationFailure("account/balance-failed")
        fun wallets(value: JsonElement?) =
            (value as? JsonArray).orEmpty().mapNotNull { row ->
                val item = row as? JsonObject ?: return@mapNotNull null
                AccountWallet(
                    item.string("currency") ?: return@mapNotNull null,
                    item.string("balance") ?: return@mapNotNull null,
                )
            }
        return DeepSeekAccountBalance(wallets(result["value"]), wallets(result["bonusWallets"]))
    }

    override suspend fun runningTasks(): Boolean =
        (call("account/hasRunningAccountTasks") as? JsonPrimitive)?.booleanOrNull
            ?: throw HostProtocolException("Invalid account activity")

    override suspend fun initializeDefaultModel() {
        call("session/initializeDefaultModel")
    }
}
