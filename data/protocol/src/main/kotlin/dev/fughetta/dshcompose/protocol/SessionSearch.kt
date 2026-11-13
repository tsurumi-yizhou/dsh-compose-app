// Portions derived from DeepSeek Harness, MIT, Copyright (c) 2026 DeepSeek.
package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.sessions.search.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

suspend fun ProtocolClient.searchSessions(query: String): SessionSearchPage =
    withContext(Dispatchers.Default) {
        val result =
            connection
                .call(
                    "session/search",
                    buildJsonObject { putJsonObject("request") { put("query", query) } },
                )
                .objectValue()
        val items =
            result["items"] as? JsonArray ?: throw HostProtocolException("Missing search hits")
        SessionSearchPage(
            items
                .map { value ->
                    value.objectValue().let {
                        SessionContentHit(
                            it.requiredString("sessionId"),
                            it.string("snippet").orEmpty(),
                        )
                    }
                }
                .toPersistentList(),
            result.boolean("hasMore") == true,
        )
    }
