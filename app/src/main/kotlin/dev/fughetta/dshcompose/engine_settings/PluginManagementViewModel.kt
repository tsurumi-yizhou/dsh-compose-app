@file:Suppress("REDUNDANT_ELSE_IN_WHEN")

package dev.fughetta.dshcompose.engine_settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.plugins.CordisStopOutcome
import dev.fughetta.dshcompose.domain.plugins.PluginApplication
import dev.fughetta.dshcompose.domain.plugins.PluginMarketEntry
import dev.fughetta.dshcompose.domain.plugins.PluginOperationResult
import dev.fughetta.dshcompose.domain.plugins.PluginsRepository
import dev.fughetta.dshcompose.domain.plugins.cordisInventoryEvents
import dev.fughetta.dshcompose.ui.engine_settings.*
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PluginManagementViewModel(
    parent: CoroutineScope? = null,
    private val market: PluginsRepository,
    private val onRestartRequested: suspend () -> Unit = {},
) : ViewModel() {
    private val scope = parent ?: viewModelScope
    private val mutable = MutableStateFlow(PluginManagementState())
    val state: StateFlow<PluginManagementState> = mutable.asStateFlow()
    private var owner: PluginsRepository? = null
    private var epoch = 0L
    private var observing = false
    private var read: Job? = null
    private var write: Job? = null
    private var marketRead: Job? = null
    private var filtering: Job? = null

    fun connect(client: PluginsRepository?, isLocal: Boolean = false) {
        if (owner === client && state.value.local == isLocal) return
        epoch++
        read?.cancel()
        write?.cancel()
        owner = client
        change {
            PluginManagementState(
                connected = client != null,
                local = isLocal,
                canInstall = client?.canInstallPlugins == true,
                cordisReadable = client?.canReadCordis == true,
                market = it.market,
                marketRows = it.marketRows,
            )
        }
        if (observing) refresh()
    }

    fun hostEvent(event: ToolPayload.Obj) {
        val name = event.text("event")
        if (name == "plugin-manager/changed" || name in cordisInventoryEvents) {
            if (observing && !state.value.busy) refresh()
        }
        val notice = owner?.notice(event) ?: return
        if (notice.requestId != state.value.requestId) return
        change { current ->
            current.copy(
                phase = notice.phase ?: current.phase,
                output =
                    notice.text?.let { (current.output + it).takeLast(128 * 1024) }
                        ?: current.output,
            )
        }
    }

    fun action(action: PluginManagementAction) {
        when (action) {
            is PluginManagementAction.Observe -> {
                observing = action.value
                if (observing) refresh() else read?.cancel()
            }
            PluginManagementAction.Refresh -> {
                refresh()
                if (state.value.marketTab) loadMarket()
            }
            is PluginManagementAction.Tab -> {
                change { it.copy(marketTab = action.market) }
                if (action.market && state.value.market == null) loadMarket()
            }
            is PluginManagementAction.Query -> {
                change { it.copy(query = action.value) }
                filter()
            }
            is PluginManagementAction.Category -> {
                change { it.copy(category = action.value) }
                filter()
            }
            is PluginManagementAction.Label -> {
                change { it.copy(label = action.value) }
                filter()
            }
            is PluginManagementAction.Sort -> {
                change { it.copy(sort = action.value) }
                filter()
            }
            is PluginManagementAction.SelectMarket ->
                change { it.copy(selectedMarket = action.name, selectedVersion = null) }
            is PluginManagementAction.SelectVersion ->
                change { it.copy(selectedVersion = action.version) }
            PluginManagementAction.NewInstall ->
                if (state.value.canInstall && !state.value.uncertain && !state.value.busy)
                    change { it.copy(draft = PluginInstallDraft(), result = null) }
            PluginManagementAction.InstallSelected -> {
                val current = state.value
                val entry =
                    current.market?.plugins?.firstOrNull { it.name == current.selectedMarket }
                        ?: return
                val version =
                    entry.versions.firstOrNull { it.version == current.selectedVersion }
                        ?: entry.versions.firstOrNull()
                        ?: return
                if (current.canInstall && !current.busy && !current.uncertain)
                    change {
                        it.copy(
                            draft = PluginInstallDraft(version.spec, version.registry.orEmpty()),
                            result = null,
                        )
                    }
            }
            is PluginManagementAction.Spec ->
                change { it.copy(draft = it.draft?.copy(spec = action.value, inspection = null)) }
            is PluginManagementAction.Registry ->
                change {
                    it.copy(draft = it.draft?.copy(registry = action.value, inspection = null))
                }
            PluginManagementAction.Inspect -> inspect()
            PluginManagementAction.RequestInstall ->
                if (
                    state.value.draft?.inspection?.accepted == true &&
                        !state.value.busy &&
                        !state.value.uncertain
                )
                    change { it.copy(confirmation = PluginConfirmation.Install) }
            is PluginManagementAction.Request ->
                if (!state.value.busy) change { it.copy(confirmation = action.confirmation) }
            PluginManagementAction.Confirm -> confirm()
            PluginManagementAction.Dismiss -> change { it.copy(confirmation = null) }
            PluginManagementAction.CloseDraft ->
                if (!state.value.busy && !state.value.uncertain)
                    change { it.copy(draft = null, confirmation = null) }
            PluginManagementAction.CancelInstall -> cancelInstall()
            PluginManagementAction.RecoverInstall -> recover()
        }
    }

    private fun refresh() {
        val client = owner ?: return
        if (!observing || state.value.busy) return
        val own = epoch
        read?.cancel()
        change { it.copy(loading = true) }
        read = scope.launch {
            try {
                val result = client.inventory()
                if (current(client, own))
                    change {
                        it.copy(
                            loading = false,
                            bundles = result.bundles.toPersistentList(),
                            entries = result.entries,
                            cordis = result.cordis,
                            configurableIds = result.configurable,
                            cordisUnavailable = result.cordisUnavailable,
                            configUnavailable = result.configUnavailable,
                            error = null,
                        )
                    }
            } catch (error: TimeoutCancellationException) {
                if (current(client, own))
                    change { it.copy(loading = false, error = "PLUGIN_TIMEOUT") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(loading = false, error = code(error)) }
            }
        }
    }

    private fun loadMarket() {
        marketRead?.cancel()
        change { it.copy(marketLoading = true) }
        marketRead = scope.launch {
            try {
                val catalog = market.market().getOrThrow()
                val hasData = catalog.plugins.isNotEmpty()
                change {
                    it.copy(
                        market = catalog,
                        marketLoading = false,
                        marketError = if (hasData) null else "PLUGIN_MARKET_UNAVAILABLE",
                    )
                }
                filter()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("PluginMarket", "load failed: ${error::class.simpleName}: ${error.message}")
                change { it.copy(marketLoading = false, marketError = "PLUGIN_MARKET_UNAVAILABLE") }
            }
        }
    }

    private fun filter() {
        filtering?.cancel()
        val snapshot = state.value
        filtering = scope.launch {
            val rows =
                withContext(Dispatchers.Default) {
                    val query = snapshot.query.trim().lowercase()
                    val result =
                        snapshot.market?.plugins.orEmpty().filter { entry ->
                            (snapshot.category == null || entry.category == snapshot.category) &&
                                (snapshot.label == null || snapshot.label in entry.labels) &&
                                (query.isBlank() ||
                                    (listOf(entry.name, entry.author.orEmpty()) +
                                            entry.keywords +
                                            listOf("en", "zh").map {
                                                entry.title?.resolve(it).orEmpty() +
                                                    entry.description?.resolve(it).orEmpty()
                                            })
                                        .any { query in it.lowercase() })
                        }
                    val sort = snapshot.sort
                    if (sort == null) result
                    else
                        result.sortedWith(
                            compareBy<PluginMarketEntry> {
                                    it.sortKeys[sort] ?: Double.NEGATIVE_INFINITY
                                }
                                .let {
                                    if (snapshot.market?.sorts?.get(sort)?.second != false)
                                        it.reversed()
                                    else it
                                },
                        )
                }
            change { it.copy(marketRows = rows.toPersistentList()) }
        }
    }

    private fun inspect() {
        val client = owner ?: return
        val draft = state.value.draft ?: return
        if (state.value.busy || state.value.uncertain || draft.spec.isBlank()) return
        val own = epoch
        operate(client) {
            val result =
                client.inspect(
                    draft.spec.trim(),
                    draft.registry.trim().takeIf(String::isNotEmpty),
                )
            if (current(client, own)) change { it.copy(draft = draft.copy(inspection = result)) }
        }
    }

    private fun confirm() {
        val client = owner ?: return
        val own = epoch
        val confirmation = state.value.confirmation ?: return
        change { it.copy(confirmation = null) }
        when (confirmation) {
            PluginConfirmation.Restart ->
                if (
                    state.value.local &&
                        state.value.result?.application == PluginApplication.RestartRequired
                )
                    operate(client) { onRestartRequested() }
            is PluginConfirmation.Reload ->
                if (
                    state.value.local &&
                        state.value.bundles.any {
                            it.name == confirmation.name &&
                                it.source?.let { source ->
                                    source.startsWith("file:") ||
                                        source.startsWith("/") ||
                                        source.startsWith("./") ||
                                        source.startsWith("../")
                                } == true
                        }
                )
                    operate(client) { onRestartRequested() }
            PluginConfirmation.Install -> install(false)
            PluginConfirmation.Builds -> install(true)
            else ->
                operate(client) {
                    val result =
                        when (confirmation) {
                            is PluginConfirmation.BundleEnabled -> {
                                val row =
                                    state.value.bundles.firstOrNull {
                                        it.name == confirmation.name && it.readOnlyReason == null
                                    } ?: return@operate
                                client.setBundleEnabled(row.name, confirmation.enabled)
                            }
                            is PluginConfirmation.EntryEnabled -> {
                                val row =
                                    state.value.entries.firstOrNull {
                                        it.entryId == confirmation.id && it.readOnlyReason == null
                                    } ?: return@operate
                                client.setPluginEnabled(row.entryId, confirmation.enabled)
                            }
                            is PluginConfirmation.Remove -> {
                                val row =
                                    state.value.bundles.firstOrNull {
                                        it.name == confirmation.name && it.removable && it.installed
                                    } ?: return@operate
                                client.removeBundle(row.name)
                            }
                            is PluginConfirmation.Stop -> {
                                val row =
                                    state.value.cordis.firstOrNull {
                                        it.agentId == confirmation.agent &&
                                            it.pluginId == confirmation.plugin
                                    } ?: return@operate
                                val outcome = client.stopCordis(row)
                                if (outcome == CordisStopOutcome.Missing)
                                    throw OperationFailure("CORDIS_MISSING")
                                null
                            }
                            is PluginConfirmation.Undefine -> {
                                val row =
                                    state.value.cordis.firstOrNull {
                                        it.agentId == confirmation.agent &&
                                            it.pluginId == confirmation.plugin
                                    } ?: return@operate
                                if (!client.removeCordis(row))
                                    throw OperationFailure("CORDIS_REMOVE_REFUSED")
                                null
                            }
                            else -> null
                        }
                    if (current(client, own)) change { it.copy(result = result) }
                }
        }
    }

    private fun install(approve: Boolean) {
        val client = owner ?: return
        val draft = state.value.draft ?: return
        if (
            !state.value.canInstall ||
                state.value.busy ||
                state.value.uncertain ||
                draft.inspection?.accepted != true
        )
            return
        val builds = if (approve) state.value.result?.pendingBuilds.orEmpty() else emptyList()
        if (approve && builds.isEmpty()) return
        val id = UUID.randomUUID().toString()
        val own = epoch
        change {
            it.copy(
                requestId = id,
                busy = true,
                uncertain = false,
                output = "",
                phase = null,
                error = null,
            )
        }
        read?.cancel()
        write = scope.launch {
            try {
                val result =
                    client.install(
                        draft.spec.trim(),
                        id,
                        draft.registry.trim().takeIf(String::isNotEmpty),
                        approvedBuilds = builds,
                    )
                if (current(client, own)) completed(result)
            } catch (error: CancellationException) {
                if (current(client, own)) change { it.copy(uncertain = true) }
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(error = code(error), uncertain = true) }
            } finally {
                if (current(client, own)) {
                    change { it.copy(busy = false) }
                    refresh()
                }
            }
        }
    }

    private fun completed(result: PluginOperationResult) {
        change {
            it.copy(
                result = result,
                output = (it.output + result.packageResult?.output.orEmpty()).takeLast(128 * 1024),
                uncertain = false,
                confirmation =
                    if (result.pendingBuilds.isNotEmpty()) PluginConfirmation.Builds else null,
            )
        }
    }

    private fun recover() {
        val client = owner ?: return
        val id = state.value.requestId ?: return
        if (!state.value.uncertain || state.value.busy) return
        val own = epoch
        operate(client, recover = true) {
            val result = client.waitForInstall(id)
            if (!current(client, own)) return@operate
            if (result == null)
                change { it.copy(error = "INSTALL_RESULT_UNKNOWN", uncertain = true) }
            else completed(result)
        }
    }

    private fun cancelInstall() {
        val client = owner ?: return
        val id = state.value.requestId ?: return
        val own = epoch
        scope.launch {
            try {
                val result = client.cancelInstall(id)
                if (current(client, own)) change { it.copy(phase = result.name) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(error = code(error)) }
            }
        }
    }

    private fun operate(
        client: PluginsRepository,
        recover: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (state.value.busy || state.value.uncertain && !recover) return
        val own = epoch
        read?.cancel()
        change { it.copy(busy = true, error = null) }
        write = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(client, own)) change { it.copy(error = code(error)) }
            } finally {
                if (current(client, own)) {
                    change { it.copy(busy = false) }
                    refresh()
                }
            }
        }
    }

    private fun current(client: PluginsRepository, own: Long) = owner === client && epoch == own

    private fun change(transform: (PluginManagementState) -> PluginManagementState) {
        mutable.value = transform(state.value).copy(revision = state.value.revision + 1)
    }

    private fun code(error: Exception) = operationErrorCode(error) ?: "PLUGIN_OPERATION_FAILED"

    override fun onCleared() {
        close()
    }

    fun close() {
        epoch++
        owner = null
        read?.cancel()
        write?.cancel()
        marketRead?.cancel()
        filtering?.cancel()
        market.close()
    }
}
