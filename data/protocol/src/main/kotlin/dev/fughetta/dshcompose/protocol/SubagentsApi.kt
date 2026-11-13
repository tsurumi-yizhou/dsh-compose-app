package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.domain.sessions.*
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.serialization.json.*

fun SubagentAddress.json(): JsonObject = buildJsonObject {
    put("kind", "subagent")
    put("parentSessionId", parentSessionId)
    put("childSessionId", childSessionId)
    put(
        "mode",
        if (mode in setOf(SubagentMode.OneShot, SubagentMode.Continuable)) mode.wire else "unknown",
    )
}

private val subagentRoutes = WeakHashMap<ProtocolClient, MutableMap<String, SubagentAddress>>()

fun ProtocolClient.registerSubagentRoute(route: SubagentAddress) =
    synchronized(subagentRoutes) {
        require(
            route.mode != SubagentMode.External &&
                route.parentSessionId.isNotBlank() &&
                route.childSessionId.isNotBlank(),
        )
        subagentRoutes.getOrPut(this) { mutableMapOf() }[route.childSessionId] = route
    }

fun ProtocolClient.subagentRoute(id: String): SubagentAddress? =
    synchronized(subagentRoutes) { subagentRoutes[this]?.get(id) }

fun ProtocolClient.clearSubagentRoutes() =
    synchronized(subagentRoutes) {
        subagentRoutes.remove(this)
        Unit
    }

fun ProtocolClient.sessionAddress(id: String): JsonObject =
    subagentRoute(id)?.json()
        ?: buildJsonObject {
            put("kind", "session")
            put("sessionId", id)
        }

suspend fun ProtocolClient.resolveSubagentMode(route: SubagentAddress): SubagentAddress {
    if (route.mode != SubagentMode.Unknown) return route
    val values = projections(route.childSessionId)?.get("values") as? JsonObject
    val identity = values?.get("subagent") as? JsonObject
    return route
        .copy(mode = SubagentMode.parse((identity?.get("mode") as? JsonPrimitive)?.contentOrNull))
        .also { registerSubagentRoute(it) }
}

suspend fun ProtocolClient.promptSubagent(
    route: SubagentAddress,
    text: String,
    images: List<JsonObject> = emptyList(),
    requestId: String = UUID.randomUUID().toString(),
    mode: PromptMode = PromptMode.Queue,
    timeZone: String? = null,
): String {
    val resolved = resolveSubagentMode(route)
    if (resolved.mode != SubagentMode.Continuable)
        throw HostRemoteException("subagent/not-resumable")
    connection.call(
        "subagents/prompt",
        buildJsonObject {
            putJsonObject("request") {
                put("requestId", requestId)
                put("parentSessionId", resolved.parentSessionId)
                put("childSessionId", resolved.childSessionId)
                put("mode", "continuable")
                put("delivery", mode.wire)
                putJsonArray("content") {
                    if (text.isNotEmpty())
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    images.forEach(::add)
                }
                timeZone?.let { put("clientTimeZone", it) }
            }
        },
    )
    return requestId
}

suspend fun ProtocolClient.interruptSubagent(route: SubagentAddress) {
    val resolved = resolveSubagentMode(route)
    if (resolved.mode != SubagentMode.Continuable)
        throw HostRemoteException("subagent/not-resumable")
    connection.call(
        "subagents/interruptByParent",
        buildJsonObject {
            put("childSessionId", resolved.childSessionId)
            put("parentSessionId", resolved.parentSessionId)
            put("mode", "continuable")
        },
    )
}
