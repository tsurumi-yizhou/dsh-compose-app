package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.presets.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

const val AGENT_PRESET_MODULE = "@deepseek-ai/dsh-agent-preset"

suspend fun ProtocolClient.agentPresets(): PersistentList<AgentPreset> =
    withContext(Dispatchers.Default) {
        val value = connection.call("agentPresets/list").objectValue()
        (value["presets"] as? JsonArray ?: throw HostProtocolException("Missing presets"))
            .map { raw ->
                raw.objectValue().let {
                    AgentPreset(
                        it.requiredString("id"),
                        it.boolean("isDefault") == true,
                        it.string("name"),
                        it.string("description"),
                        it.string("broken"),
                        (it["order"] as? JsonPrimitive)?.longOrNull,
                    )
                }
            }
            .toPersistentList()
    }

suspend fun ProtocolClient.readAgentPreset(id: String): AgentPresetDocument =
    withContext(Dispatchers.Default) {
        val value =
            connection
                .call("agentPresets/read", buildJsonObject { put("agentPreset", id) })
                .objectValue()
        AgentPresetDocument(
            value.requiredString("agentPreset"),
            value.string("content") ?: throw HostProtocolException("Missing preset document"),
        )
    }

suspend fun ProtocolClient.agentPresetDefaults(): AgentPresetDefaults? {
    val response = connection.call("settings/describe").objectValue()
    val section =
        (response["namespaces"] as? JsonArray)
            .orEmpty()
            .map { it.objectValue() }
            .firstOrNull { it.string("ns") == "agent-preset-registry" } ?: return null
    return AgentPresetDefaults(
        (section["revision"] as? JsonPrimitive)?.longOrNull
            ?: throw HostProtocolException("Missing preset settings revision"),
        response.boolean("writable") != false && section.boolean("writable") != false,
    )
}

suspend fun ProtocolClient.setDefaultAgentPreset(id: String, previous: AgentPresetDefaults) {
    require(previous.writable)
    connection.call(
        "settings/update",
        buildJsonObject {
            put("ns", "agent-preset-registry")
            put("expectedRevision", previous.revision)
            putJsonObject("patch") { put("selectedDefault", id) }
        },
    )
}

suspend fun ProtocolClient.selectAgentPreset(sessionId: String, preset: String): String {
    val value =
        connection.call(
            "agentPresets/select",
            buildJsonObject {
                put("agentId", sessionId)
                put("agentPreset", preset)
            },
        )
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw HostProtocolException("Missing selected preset")
}
