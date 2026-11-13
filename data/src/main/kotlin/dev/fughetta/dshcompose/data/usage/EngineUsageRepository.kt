package dev.fughetta.dshcompose.data.usage

import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.domain.account.DeepSeekAccountBalance
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.usage.*
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.protocol.toToolPayload
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Uses the owner's existing selected-session follow, never a Host-wide control/history stream. */
class EngineUsageRepository
private constructor(
    private val ownerScope: CoroutineScope?,
    private val source: ProtocolClient?,
    private val refreshAccount: () -> Unit,
    private val connectionActive: () -> Boolean,
) : UsageRepository {
    constructor(
        scope: CoroutineScope,
        refreshAccount: () -> Unit,
    ) : this(scope, null, refreshAccount, { true })

    constructor(
        client: ProtocolClient,
        connectionActive: () -> Boolean = { true },
    ) : this(null, client, {}, connectionActive)

    private val scope: CoroutineScope
        get() = checkNotNull(ownerScope)

    override val generation = source?.mux?.state?.value?.generation

    override suspend fun projections(sessionId: String): ToolPayload? {
        val source = source ?: return null
        return source.withGeneration(generation, { !closed && connectionActive() }) {
            val value =
                source.connection.call(
                    "session/projections",
                    buildJsonObject { putJsonObject("request") { put("sessionId", sessionId) } },
                )
            if (value == JsonNull) null else value.objectValue().toToolPayload()
        }
    }

    private val mutable = MutableStateFlow(UsageReading())
    override val state: StateFlow<UsageReading> = mutable.asStateFlow()
    private val balanceClient = lazy { ProviderBalanceClient() }
    private var client: UsageRepository? = null
    private var session: String? = null
    private var connection = 0L
    private var seedTicket = 0L
    private var seed: Job? = null
    private var refresh: Job? = null
    private var panel: Job? = null
    private var balanceJob: Job? = null
    private val fold = dev.fughetta.dshcompose.domain.usage.UsageFold()
    private var lastRead = 0L
    private var readAgain = false
    private var lastBalanceAttempt = 0L
    private var checkedKey = false
    private var closed = false
    private var panelVisible = false

    override fun connect(owner: UsageRepository?, hostKey: String?, sessionId: String?) {
        val hostChanged = owner !== client || hostKey != state.value.hostKey
        if (closed || !hostChanged && sessionId == session) return
        seed?.cancel()
        refresh?.cancel()
        seedTicket++
        fold.clear()
        session = sessionId
        lastRead = 0L
        readAgain = false
        if (hostChanged) {
            connection++
            balanceJob?.cancel()
            client = owner
            lastBalanceAttempt = 0
            checkedKey = false
            panelVisible = false
            mutable.value = UsageReading(hostKey, sessionId, connection = connection)
        } else mutable.value = state.value.copy(sessionId = sessionId, usage = SessionUsage())
        query()
        updatePanel()
    }

    /** Forward only the already-watched selected session's update, in the same order. */
    override fun sessionUpdate(update: UsageChange) {
        if (closed || client == null || session == null) return
        when (update) {
            UsageChange.Reset -> {
                seed?.cancel()
                refresh?.cancel()
                seedTicket++
                fold.clear()
                publish()
            }
            is UsageChange.Snapshot -> {
                seed?.cancel()
                seedTicket++
                fold.seed(update.projections, replace = true)
                publish()
            }
            is UsageChange.Record ->
                if (
                    update.type in
                        setOf(
                            "user/message",
                            "assistant/message",
                            "turn/start",
                            "turn/end",
                            "request/context",
                            "model/selection",
                        )
                )
                    scheduleRead()
        }
    }

    private fun scheduleRead() {
        if (closed || refresh?.isActive == true) return
        refresh = scope.launch {
            delay((1000 - (System.currentTimeMillis() - lastRead)).coerceAtLeast(0))
            query()
        }
    }

    private fun query() {
        if (closed) return
        val owner = client ?: return
        val id = session ?: return
        if (seed?.isActive == true) {
            readAgain = true
            return
        }
        val ticket = ++seedTicket
        val own = connection
        val expected = owner.generation
        lastRead = System.currentTimeMillis()
        seed = scope.launch {
            try {
                val current = owner.projections(id)
                if (
                    connection == own &&
                        session == id &&
                        seedTicket == ticket &&
                        owner.generation === expected
                ) {
                    if (current != null) fold.seed(current)
                    publish()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (connection == own && session == id && seedTicket == ticket)
                    mutable.value = state.value.copy(error = "usage/seed-failed")
            } finally {
                if (connection == own && session == id && seedTicket == ticket && readAgain) {
                    readAgain = false
                    seed = null
                    scheduleRead()
                }
            }
        }
    }

    private fun publish() {
        mutable.value = state.value.copy(usage = fold.usage(), error = null)
    }

    private fun updatePanel() {
        panel?.cancel()
        if (panelVisible && client != null && session != null)
            panel = scope.launch {
                while (isActive) {
                    delay(5000)
                    query()
                }
            }
    }

    override fun account(value: DeepSeekAccountBalance?) {
        if (balanceJob?.isActive == true || checkedKey) return
        val balance = value?.let(ProviderBalance::account)
        mutable.value =
            state.value.copy(
                balance = balance,
                balanceStatus = if (balance != null) "ok" else "no-key",
                balanceSource =
                    if (balance != null) UsageBalanceSource.Account else UsageBalanceSource.None,
                balanceCheckedAt = if (balance != null) System.currentTimeMillis() else null,
            )
    }

    override fun observePanel(active: Boolean) {
        panelVisible = active
        updatePanel()
    }

    override fun refresh(immediate: Boolean) {
        if (closed) return
        if (immediate) query() else scheduleRead()
        refreshBalance()
    }

    private fun refreshBalance() {
        val now = System.currentTimeMillis()
        if (now - lastBalanceAttempt < 60000) return
        lastBalanceAttempt = now
        refreshAccount()
    }

    override fun checkKey(key: ProviderKey) {
        if (closed || balanceJob?.isActive == true) {
            key.close()
            return
        }
        val own = connection
        checkedKey = true
        mutable.value =
            state.value.copy(
                balanceLoading = true,
                error = null,
                balance = null,
                balanceCheckedAt = null,
                balanceSource = UsageBalanceSource.ApiKey,
                balanceStatus = "unknown",
            )
        balanceJob = scope.launch {
            try {
                when (val result = balanceClient.value.read(key)) {
                    is ProviderBalanceResult.Ready ->
                        if (connection == own)
                            mutable.value =
                                state.value.copy(
                                    balance = result.balance,
                                    balanceStatus = "ok",
                                    balanceCheckedAt = System.currentTimeMillis(),
                                    balanceRevision = state.value.balanceRevision + 1,
                                )
                    ProviderBalanceResult.InvalidKey ->
                        if (connection == own)
                            mutable.value =
                                state.value.copy(balance = null, balanceStatus = "invalid-key")
                    ProviderBalanceResult.Unavailable ->
                        if (connection == own)
                            mutable.value = state.value.copy(balanceStatus = "unavailable")
                }
            } finally {
                key.close()
                if (connection == own) mutable.value = state.value.copy(balanceLoading = false)
            }
        }
        balanceJob?.invokeOnCompletion { key.close() }
    }

    override fun close() {
        closed = true
        connection++
        seed?.cancel()
        refresh?.cancel()
        panel?.cancel()
        balanceJob?.cancel()
        if (balanceClient.isInitialized()) balanceClient.value.close()
    }
}
