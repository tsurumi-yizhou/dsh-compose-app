package dev.fughetta.dshcompose.data.permissions

import dev.fughetta.dshcompose.data.link.withGeneration
import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.permissions.*
import dev.fughetta.dshcompose.domain.permissions.PermissionsRepository
import dev.fughetta.dshcompose.domain.skills.validSkillName
import dev.fughetta.dshcompose.protocol.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** PermissionsRepository backed by one engine connection. */
class EnginePermissionsRepository(
    private val client: ProtocolClient,
    private val connectionActive: () -> Boolean = { true },
) : PermissionsRepository {
    private val connectionGeneration = client.mux.state.value.generation

    private suspend fun permissionCatalog(): PermissionCatalog =
        withContext(Dispatchers.Default) {
            val result = client.connection.call("permissionPresets/catalog").objectValue()
            fun options(key: String): PersistentList<PermissionOption> =
                (result[key] as? JsonArray
                        ?: throw HostProtocolException("Missing permission choices"))
                    .mapNotNull { raw ->
                        val value = raw as? JsonObject ?: return@mapNotNull null
                        val id =
                            value.string("value")?.takeIf(::validSkillName)
                                ?: return@mapNotNull null
                        PermissionOption(
                            id,
                            value.string("name") ?: id,
                            value.string("description"),
                        )
                    }
                    .distinctBy { it.value }
                    .toPersistentList()
            PermissionCatalog(
                options("options"),
                options("defaultOptions"),
                result.requiredString("defaultPreset"),
            )
        }

    private suspend fun permissionDefaults(): PermissionDefaults? =
        withContext(Dispatchers.Default) {
            val result = client.connection.call("settings/describe").objectValue()
            val section =
                (result["namespaces"] as? JsonArray)
                    .orEmpty()
                    .map { it.objectValue() }
                    .firstOrNull { it.string("ns") == "permission" } ?: return@withContext null
            PermissionDefaults(
                section.requiredLong("revision"),
                result.boolean("writable") != false && section.boolean("writable") != false,
                (section["value"] as? JsonObject)?.string("defaultPreset"),
            )
        }

    private suspend fun currentPermission(sessionId: String): String? {
        val response =
            client.connection.call(
                "session/projections",
                buildJsonObject { putJsonObject("request") { put("sessionId", sessionId) } },
            )
        if (response == JsonNull) throw HostRemoteException("session/not-found")
        return ((response.objectValue()["values"] as? JsonObject)?.get("permissions")
                as? JsonObject)
            ?.string("currentValue")
    }

    override suspend fun change(
        sessionId: String?,
        preset: String,
        defaults: PermissionDefaults?,
        stillCurrent: () -> Boolean,
    ): PermissionWriteback? = changePermission(this, sessionId, preset, defaults, stillCurrent)

    override suspend fun snapshot(sessionId: String?): PermissionSnapshot =
        client.withGeneration(connectionGeneration, connectionActive) {
            coroutineScope {
                val catalog = async { permissionCatalog() }
                val defaults = async { permissionDefaults() }
                val current = async { sessionId?.let { currentPermission(it) } }
                PermissionSnapshot(catalog.await(), defaults.await(), current.await())
            }
        }

    override suspend fun setPreset(sessionId: String, preset: String): CommandResult =
        client.withGeneration(connectionGeneration, connectionActive) {
            require(validSkillName(preset))
            return@withGeneration client.executeCommand(sessionId, "/permission $preset")
        }

    override suspend fun setDefault(preset: String, previous: PermissionDefaults): Unit =
        client.withGeneration(connectionGeneration, connectionActive) {
            require(previous.writable && validSkillName(preset))
            client.connection.call(
                "settings/update",
                buildJsonObject {
                    put("ns", "permission")
                    put("expectedRevision", previous.revision)
                    putJsonObject("patch") { put("defaultPreset", preset) }
                },
            )
            Unit
        }
}
