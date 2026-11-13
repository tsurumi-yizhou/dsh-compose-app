package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.skills.validSkillName
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

suspend fun ProtocolClient.listCommands(sessionId: String): PersistentList<SlashCommand> =
    withContext(Dispatchers.Default) {
        val result =
            connection.call("commands/list", buildJsonObject { put("agentId", sessionId) })
                as? JsonArray ?: throw HostProtocolException("Missing command catalog")
        result
            .mapNotNull { raw ->
                val value = raw as? JsonObject ?: return@mapNotNull null
                val name = value.string("name")?.takeIf(::validSkillName) ?: return@mapNotNull null
                val input = value["input"] as? JsonObject
                SlashCommand(
                    name,
                    name,
                    value.string("description").orEmpty(),
                    input?.string("hint") ?: if (input != null) "" else null,
                    definitionId = value.string("definitionId"),
                    acceptsAttachments = input?.boolean("attachments") == true,
                )
            }
            .distinctBy { it.name }
            .toPersistentList()
    }

suspend fun ProtocolClient.executeCommand(sessionId: String, line: String): CommandResult =
    withContext(Dispatchers.Default) {
        val input =
            parseSlashInput(line) ?: throw IllegalArgumentException("Expected a slash command")
        val args = buildJsonObject {
            put("agentId", sessionId)
            put("line", line.trim())
            putJsonArray("submittedAttachments") {}
        }
        val timeout = if (input.token == "compact") 1_800_000L else 65_000L
        val response =
            withTimeout(timeout) { connection.callWithTimeout("commands/execute", args, timeout) }
                .objectValue()
        val result =
            response["result"]?.objectValue()
                ?: throw HostProtocolException("Missing command result")
        CommandResult(
            response.string("commandId"),
            result.string("kind") == "success",
            result.string("text"),
        )
    }
