package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.engine.BridgeClientInventory
import dev.fughetta.dshcompose.domain.engine.BridgeClientPackage
import dev.fughetta.dshcompose.domain.engine.ClientSlotOccupant
import dev.fughetta.dshcompose.domain.engine.WebBootEntry
import dev.fughetta.dshcompose.domain.engine.WebBootGraph
import io.ktor.client.request.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun JsonObject.listStrings(key: String) =
    (get(key) as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }

fun parseWebBootGraph(html: String): WebBootGraph? {
    if (html.length > 4 * 1024 * 1024) return null
    val assignment =
        Regex(
            "(?:globalThis|window|self)\\s*(?:\\.\\s*__DSH_BOOT__|\\[\\s*[\"']__DSH_BOOT__[\"']\\s*\\])\\s*=\\s*",
        )
    for (match in assignment.findAll(html)) {
        val start = match.range.last + 1
        if (html.getOrNull(start) != '{') continue
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until html.length) {
            val char = html[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else
                when (char) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> depth--
                }
            if (depth != 0) continue
            val raw =
                runCatching {
                    Json.parseToJsonElement(html.substring(start, index + 1)) as? JsonObject
                }
                    .getOrNull() ?: break
            val entries = raw["entries"] as? JsonArray ?: break
            return WebBootGraph(
                raw.string("rev").orEmpty(),
                (raw["release"] as? JsonObject)?.string("version"),
                entries
                    .mapNotNull { body ->
                        (body as? JsonObject)?.let { row ->
                            row.string("id")?.takeIf(String::isNotBlank)?.let {
                                WebBootEntry(
                                    it,
                                    row.string("url").orEmpty(),
                                    row.string("rev").orEmpty(),
                                    row.listStrings("inject"),
                                    row.listStrings("external"),
                                    row.boolean("immediately") == true,
                                )
                            }
                        }
                    }
                    .distinctBy { it.id }
                    .toPersistentList(),
            )
        }
    }
    return null
}

suspend fun ProtocolClient.readWebBootGraph(): WebBootGraph? {
    val direct =
        connection as? HostConnection ?: throw HostRemoteException("CLIENT_INVENTORY_UNSUPPORTED")
    return withTimeout(15000) {
        direct.exchange(
            direct.request("/compose/editor/resource").apply { url.parameters.append("path", "/") },
        ) { response ->
            if (response.status.value != 200)
                throw HostRemoteException("client-inventory/http-${response.status.value}")
            val html = response.boundedBytes(4L * 1024 * 1024).toString(Charsets.UTF_8)
            withContext(Dispatchers.Default) { parseWebBootGraph(html) }
        }
    }
}

suspend fun ProtocolClient.readBridgeClientInventory(): BridgeClientInventory {
    val direct =
        connection as? HostConnection ?: throw HostRemoteException("CLIENT_INVENTORY_UNSUPPORTED")
    val raw =
        withTimeout(15000) {
            direct.exchange(direct.request("/compose/extensions/v1/inventory")) { response ->
                if (response.status.value != 200)
                    throw HostRemoteException("client-inventory/http-${response.status.value}")
                response.jsonObject(4L * 1024 * 1024)
            }
        }
    return withContext(Dispatchers.Default) {
        val packages =
            (raw["packages"] as? JsonArray)
                .orEmpty()
                .mapNotNull { body ->
                    (body as? JsonObject)?.let { row ->
                        row.string("name")?.let {
                            BridgeClientPackage(
                                it,
                                row.boolean("hasClient") == true,
                                row.string("rev"),
                                row.listStrings("panels"),
                                row.string("pet"),
                            )
                        }
                    }
                }
                .toPersistentList()
        val slots =
            (raw["slots"] as? JsonObject)
                ?.mapValues { (slot, body) ->
                    (body as? JsonArray)
                        .orEmpty()
                        .mapNotNull { item ->
                            (item as? JsonObject)?.let { row ->
                                row.string("package")
                                    ?.takeIf { it != "dsh-compose-bridge" }
                                    ?.let {
                                        ClientSlotOccupant(
                                            slot,
                                            it,
                                            row.string("cell").orEmpty(),
                                            row.long("order") ?: 0,
                                            parseLocalizedText(row["label"]),
                                            row.boolean("official") == true,
                                        )
                                    }
                            }
                        }
                        .toPersistentList()
                }
                ?.toPersistentMap()
        BridgeClientInventory(
            raw.string("bootRev"),
            (raw["release"] as? JsonObject)?.string("version"),
            packages,
            slots,
            raw.boolean("routeGuard") == true,
            contract = (raw.long("contract") ?: 0L).toInt(),
            capabilities = raw.listStrings("capabilities"),
        )
    }
}
