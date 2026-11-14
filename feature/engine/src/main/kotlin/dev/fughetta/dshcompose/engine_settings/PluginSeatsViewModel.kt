package dev.fughetta.dshcompose.engine_settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fughetta.dshcompose.domain.engine.negotiateBridgeCapabilities
import dev.fughetta.dshcompose.domain.operationErrorCode
import dev.fughetta.dshcompose.domain.plugins.PluginUiRepository
import dev.fughetta.dshcompose.ui.engine_settings.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PluginSeatsViewModel : ViewModel() {
    private val scope
        get() = viewModelScope

    private val mutable = MutableStateFlow(PluginSeatsState())
    val state: StateFlow<PluginSeatsState> = mutable.asStateFlow()
    private var owner: PluginUiRepository? = null
    private var epoch = 0L
    private var observing = false
    private var reading: Job? = null

    fun connect(client: PluginUiRepository?) {
        if (owner === client) return
        epoch++
        reading?.cancel()
        owner = client
        mutable.value =
            PluginSeatsState(
                connected = client != null,
                webReachable = client?.webReachable == true,
            )
        if (observing) refresh()
    }

    fun hostEvent(name: String?) {
        if (
            name in
                setOf(
                    "plugin-manager/changed",
                    "dsh-compose/inventory-changed",
                    "dsh-compose/occupancy-changed",
                ) && observing
        )
            refresh()
    }

    fun action(action: PluginSeatsAction) {
        when (action) {
            is PluginSeatsAction.Observe -> {
                observing = action.value
                if (observing) refresh() else reading?.cancel()
            }
            PluginSeatsAction.Refresh -> refresh()
        }
    }

    private fun refresh() {
        val client = owner ?: return
        if (!state.value.webReachable || !observing) return
        val own = epoch
        reading?.cancel()
        change { it.copy(loading = true, error = null) }
        reading = scope.launch {
            try {
                val (graph, inventory) =
                    coroutineScope {
                        val graph = async { client.bootGraph() }
                        val inventory = async { client.bridgeInventory() }
                        graph.await() to inventory.await()
                    }
                if (owner !== client || epoch != own) return@launch
                val webShell =
                    graph?.entries?.any {
                        it.id == "@deepseek-ai/dsh-client-ui-layout" ||
                            it.url.contains("dsh-client-ui-layout")
                    } == true
                val occupancySupported =
                    inventory.capabilities.isEmpty() ||
                        negotiateBridgeCapabilities(setOf("occupancy"), inventory.capabilities)
                            .isNotEmpty()
                val current =
                    graph != null &&
                        inventory.bootRevision == graph.revision &&
                        inventory.slots != null &&
                        occupancySupported
                change {
                    it.copy(
                        loading = false,
                        routeGuard = inventory.routeGuard,
                        occupancyCurrent = current,
                        webShell = webShell,
                        packages = inventory.packages,
                        occupants =
                            if (current)
                                inventory.slots!!
                                    .values
                                    .flatten()
                                    .sortedBy { row -> row.order }
                                    .toPersistentList()
                            else persistentListOf(),
                    )
                }
            } catch (error: TimeoutCancellationException) {
                if (owner === client && epoch == own)
                    change { it.copy(loading = false, error = "PLUGIN_SEATS_TIMEOUT") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (owner === client && epoch == own)
                    change {
                        it.copy(
                            loading = false,
                            error = operationErrorCode(error) ?: "PLUGIN_SEATS_FAILED",
                        )
                    }
            }
        }
    }

    private fun change(transform: (PluginSeatsState) -> PluginSeatsState) {
        mutable.value = transform(state.value).copy(revision = state.value.revision + 1)
    }

    fun disconnect() {
        epoch++
        owner = null
        reading?.cancel()
    }

    override fun onCleared() {
        disconnect()
    }
}
