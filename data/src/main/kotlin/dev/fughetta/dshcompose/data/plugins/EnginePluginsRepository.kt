package dev.fughetta.dshcompose.data.plugins

import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.plugins.*
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Plugin inventory and mutations owned by one engine connection. */
class EnginePluginsRepository(
    client: ProtocolClient? = null,
    private val connectionActive: () -> Boolean = { true },
) : PluginsRepository {
    override val generation: ConnectionGeneration?
        get() = connectionGeneration

    private val source = client
    @Volatile private var closed = false
    private val client: ProtocolClient
        get() {
            if (closed) throw ConnectionClosedException()
            return source
                ?: throw dev.fughetta.dshcompose.domain.OperationFailure("plugins/offline")
        }

    private fun active() = !closed && connectionActive()

    private val catalog = PluginCatalog()
    private val connectionGeneration = source?.mux?.state?.value?.generation

    override suspend fun inventory(): PluginInventory = catalog.inventory(this)

    override suspend fun market(): Result<PluginMarketIndex> = catalog.market()

    override fun notice(
        event: dev.fughetta.dshcompose.domain.chat.ToolPayload.Obj,
    ): PluginManagerNotice? = pluginManagerNotice(event)

    override fun close() {
        if (closed) return
        closed = true
        catalog.close()
    }

    private val connection
        get() = client.connection

    override val canReadCordis: Boolean
        get() = source?.connection is HostConnection

    override val canInstallPlugins: Boolean
        get() = source?.connection?.supportsBundleRemoval == true

    override val canReadCompletePluginConfig: Boolean
        get() = source?.canReadCompletePluginConfig == true

    override suspend fun bundles(): List<PluginBundle> =
        client.withGeneration(connectionGeneration, ::active) { client.listBundles() }

    override suspend fun setBundleEnabled(name: String, enabled: Boolean): PluginOperationResult =
        client.withGeneration(connectionGeneration, ::active) {
            parsePluginOperation(
                connection.call(
                    "pluginManager/setBundleEnabled",
                    buildJsonObject {
                        put("name", name)
                        put("enabled", enabled)
                    },
                ),
            )
        }

    override suspend fun removeBundle(name: String): PluginOperationResult =
        client.withGeneration(connectionGeneration, ::active) {
            parsePluginOperation(
                pluginManagementCall(
                    "pluginManager/removeBundle",
                    buildJsonObject { put("name", name) },
                    600_000,
                ),
            )
        }

    private suspend fun pluginManagementCall(
        endpoint: String,
        args: JsonObject,
        timeoutMillis: Long,
    ): JsonElement =
        withTimeout(timeoutMillis) { connection.callWithTimeout(endpoint, args, timeoutMillis) }

    override suspend fun plugins(): PersistentList<PluginEntry> =
        client.withGeneration(connectionGeneration, ::active) {
            withContext(Dispatchers.Default) {
                (connection.call("pluginManager/listPlugins") as? JsonArray
                        ?: throw HostProtocolException("Missing plugins"))
                    .map { item ->
                        item.objectValue().let {
                            PluginEntry(
                                it.requiredString("entryId"),
                                it.requiredString("moduleName"),
                                it.boolean("enabled") == true,
                                it.string("fiberPhase"),
                                it.string("patchId"),
                                it.string("readOnlyReason"),
                                parsePluginDisplayMeta(it["meta"]),
                            )
                        }
                    }
                    .toPersistentList()
            }
        }

    override suspend fun setPluginEnabled(
        entryId: String,
        enabled: Boolean,
    ): PluginOperationResult =
        client.withGeneration(connectionGeneration, ::active) {
            parsePluginOperation(
                connection.call(
                    "pluginManager/setPluginEnabled",
                    buildJsonObject {
                        put("id", entryId)
                        put("enabled", enabled)
                    },
                ),
            )
        }

    override suspend fun inspect(spec: String, registry: String?): PluginInspection =
        client.withGeneration(connectionGeneration, ::active) {
            val raw =
                connection
                    .call(
                        "pluginManager/inspect",
                        buildJsonObject {
                            put("spec", spec)
                            registry?.let { putJsonObject("options") { put("registry", it) } }
                        },
                    )
                    .objectValue()
            return@withGeneration PluginInspection(
                raw.string("status") != "refused",
                raw.string("kind"),
                raw.string("name"),
                raw.string("version"),
                raw.string("description"),
                raw.boolean("bundle"),
                raw.string("registry"),
                raw.string("host"),
                raw.string("problem"),
                raw.string("reason"),
            )
        }

    override suspend fun install(
        spec: String,
        requestId: String,
        registry: String?,
        approvedBuilds: List<String>,
    ): PluginOperationResult =
        client.withGeneration(connectionGeneration, ::active) {
            parsePluginOperation(
                pluginManagementCall(
                    "pluginManager/installBundle",
                    buildJsonObject {
                        put("spec", spec)
                        putJsonObject("options") {
                            put("requestId", requestId)
                            put("enabled", true)
                            put("saveExact", true)
                            registry?.let { put("registry", it) }
                            if (approvedBuilds.isNotEmpty())
                                put(
                                    "approvedBuilds",
                                    JsonArray(approvedBuilds.map(::JsonPrimitive)),
                                )
                        }
                    },
                    1_800_000,
                ),
            )
        }

    override suspend fun cancelInstall(requestId: String): PluginCancelStatus =
        client.withGeneration(connectionGeneration, ::active) {
            val raw =
                pluginManagementCall(
                        "pluginManager/cancelInstall",
                        buildJsonObject { put("requestId", requestId) },
                        300_000,
                    )
                    .objectValue()
            return@withGeneration when (raw.string("status")) {
                "cancelled" -> PluginCancelStatus.Cancelled
                "not-running" -> PluginCancelStatus.NotRunning
                "too-late" -> PluginCancelStatus.TooLate
                else -> throw HostProtocolException("Invalid install cancellation")
            }
        }

    override suspend fun waitForInstall(requestId: String): PluginOperationResult? =
        client.withGeneration(connectionGeneration, ::active) {
            pluginManagementCall(
                    "pluginManager/waitForInstall",
                    buildJsonObject { put("requestId", requestId) },
                    1_800_000,
                )
                .let { if (it == JsonNull) null else parsePluginOperation(it) }
        }

    override suspend fun cordis(): PersistentList<CordisPlugin> =
        client.withGeneration(connectionGeneration, ::active) {
            withContext(Dispatchers.Default) {
                if (!canReadCordis) throw HostRemoteException("CORDIS_UNSUPPORTED")
                (connection.call("dynamicCordisRunner/inventory") as? JsonArray
                        ?: throw HostProtocolException("Missing Cordis inventory"))
                    .mapNotNull { item ->
                        val raw = item as? JsonObject ?: return@mapNotNull null
                        val id = raw.string("pluginId") ?: return@mapNotNull null
                        val owner = raw.string("agentId") ?: return@mapNotNull null
                        val packages =
                            (raw["packages"] as? JsonArray)
                                .orEmpty()
                                .mapNotNull { member ->
                                    (member as? JsonObject)?.let { row ->
                                        row.string("packageId")?.let {
                                            CordisPackage(
                                                it,
                                                row.string("name").orEmpty(),
                                                row.string("purpose").orEmpty(),
                                                row.boolean("hasHostHalf") == true,
                                                row.boolean("hasClientHalf") == true,
                                            )
                                        }
                                    }
                                }
                                .toPersistentList()
                        val attempt = raw["latestRun"] as? JsonObject
                        val failure = attempt?.get("error") as? JsonObject
                        val latest =
                            if (
                                attempt?.string("pluginRunId") != null &&
                                    attempt.string("packageId") != null
                            )
                                CordisRunAttempt(
                                    attempt.requiredString("pluginRunId"),
                                    attempt.requiredString("packageId"),
                                    attempt.string("status").orEmpty(),
                                    attempt.string("mode") ?: "run",
                                    attempt.boolean("requiresApproval") == true,
                                    (attempt["host"] as? JsonObject)?.string("status"),
                                    (attempt["client"] as? JsonObject)?.string("status"),
                                    failure?.let {
                                        CordisRunFailure(
                                            it.string("phase").orEmpty(),
                                            it.string("message").orEmpty(),
                                        )
                                    },
                                )
                            else null
                        val active = raw["activeRun"] as? JsonObject
                        CordisPlugin(
                            id,
                            owner,
                            packages,
                            raw.string("currentPackageId"),
                            raw.string("nextPackageId"),
                            active?.string("pluginRunId"),
                            active?.string("packageId"),
                            latest,
                        )
                    }
                    .toPersistentList()
            }
        }

    override suspend fun stopCordis(plugin: CordisPlugin): CordisStopOutcome =
        client.withGeneration(connectionGeneration, ::active) {
            val result =
                connection
                    .call(
                        "dynamicCordisRunner/stopFromPanel",
                        buildJsonObject {
                            put("agentId", plugin.agentId)
                            put("pluginId", plugin.pluginId)
                        },
                    )
                    .objectValue()
            return@withGeneration if (result.boolean("ok") == true) CordisStopOutcome.Stopped
            else if (result.string("reason") == "not-running") CordisStopOutcome.NotRunning
            else CordisStopOutcome.Missing
        }

    override suspend fun removeCordis(plugin: CordisPlugin): Boolean =
        client.withGeneration(connectionGeneration, ::active) {
            connection
                .call(
                    "dynamicCordisRunner/undefineFromPanel",
                    buildJsonObject {
                        put("agentId", plugin.agentId)
                        put("pluginId", plugin.pluginId)
                    },
                )
                .objectValue()
                .boolean("ok") == true
        }

    override suspend fun config(): PersistentList<PluginConfigEntrySummary> =
        client.withGeneration(connectionGeneration, ::active) {
            withContext(Dispatchers.Default) {
                val result = client.pluginConfigExchange(null)
                val entries =
                    result.objectValue()["entries"] as? JsonArray
                        ?: throw HostProtocolException("Missing plugin config entries")
                entries
                    .map { item ->
                        item.objectValue().let { raw ->
                            PluginConfigEntrySummary(
                                raw.requiredString("entryId"),
                                raw.string("package"),
                                parseLocalizedText(raw["label"]),
                                raw.string("state") ?: "active",
                                raw.boolean("configurable") == true,
                                raw.string("reason"),
                                raw.string("owner"),
                                raw.string("bundle"),
                                raw.string("kind"),
                                raw.string("source"),
                                raw.boolean("writable") == true,
                            )
                        }
                    }
                    .toPersistentList()
            }
        }
}
