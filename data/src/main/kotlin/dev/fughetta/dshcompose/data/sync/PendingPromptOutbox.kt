package dev.fughetta.dshcompose.data.sync

import android.content.Context
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.OutboxDao
import dev.fughetta.dshcompose.data.database.OutboxRow
import dev.fughetta.dshcompose.data.database.SyncStates
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.messages.PendingPrompt
import dev.fughetta.dshcompose.domain.messages.PendingPromptState
import dev.fughetta.dshcompose.domain.messages.PromptMode
import java.io.File
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.*

/**
 * Durable copy of the pending prompts that a dropped connection left uncertain. The row keeps the
 * original `requestId`, so a resend after the app restarts is still de-duplicated by the engine.
 * Attachments are stored by path; a file that no longer exists is dropped on read, and the text is
 * kept so nothing is silently lost.
 */
class PendingPromptOutbox(
    private val outbox: OutboxDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun retain(prompt: PendingPrompt) {
        settle(prompt.host, prompt.requestId)
        outbox.enqueue(
            OutboxRow(
                hostId = prompt.host,
                sessionId = prompt.sessionId,
                kind = KIND,
                payload = encode(prompt).toString(),
                receiptId = prompt.requestId,
                state = SyncStates.UNKNOWN,
                createdAt = clock(),
                attempts = prompt.attempt.toInt(),
            ),
        )
    }

    suspend fun outstanding(hostId: String): List<PendingPrompt> =
        outbox
            .byKind(hostId, KIND)
            .filter { it.state in SyncStates.outstanding }
            .mapNotNull { row -> runCatching { decode(row) }.getOrNull() }
            .sortedBy { it.requestId }

    suspend fun settle(hostId: String, requestId: String) =
        outbox.removeByReceipt(hostId, requestId)

    suspend fun rejected(hostId: String, requestId: String) =
        outbox.setStateByReceipt(hostId, requestId, SyncStates.FAILED)

    private fun encode(prompt: PendingPrompt): JsonObject = buildJsonObject {
        put("requestId", prompt.requestId)
        put("home", prompt.home)
        put("sessionId", prompt.sessionId)
        put("text", prompt.text)
        prompt.quote?.let { put("quote", it) }
        put("composerText", prompt.composerText)
        put("mode", prompt.mode.name)
        prompt.timeZone?.let { put("timeZone", it) }
        prompt.errorCode?.let { put("errorCode", it) }
        put("composerOwned", prompt.composerOwned)
        put("attempt", prompt.attempt)
        prompt.itemId?.let { put("itemId", it) }
        putJsonArray("files") {
            prompt.files.forEach { file ->
                add(
                    buildJsonObject {
                        put("id", file.id)
                        put("name", file.name)
                        put("mediaType", file.mediaType)
                        put("path", file.path)
                        put("bytes", file.bytes)
                    },
                )
            }
        }
        putJsonArray("fileReceiptIds") { prompt.fileReceiptIds.forEach { add(JsonPrimitive(it)) } }
    }

    private fun decode(row: OutboxRow): PendingPrompt {
        val value = Json.parseToJsonElement(row.payload).jsonObject
        fun text(key: String) = (value[key] as? JsonPrimitive)?.contentOrNull
        val files =
            (value["files"] as? JsonArray)
                .orEmpty()
                .mapNotNull { element ->
                    val file = element as? JsonObject ?: return@mapNotNull null
                    val id = (file["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val path = (file["path"] as? JsonPrimitive)?.contentOrNull
                    val source = path?.let(::File)?.takeIf { it.exists() } ?: return@mapNotNull null
                    ComposerFile(
                        id,
                        (file["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        (file["mediaType"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        (source).absolutePath,
                        (file["bytes"] as? JsonPrimitive)?.longOrNull ?: source.length(),
                    )
                }
                .toPersistentList()
        return PendingPrompt(
            requestId = text("requestId") ?: row.receiptId,
            host = row.hostId,
            home = text("home").orEmpty(),
            sessionId = text("sessionId") ?: row.sessionId.orEmpty(),
            text = text("text").orEmpty(),
            quote = text("quote"),
            composerText = text("composerText").orEmpty(),
            files = files,
            state = PendingPromptState.Uncertain,
            itemId = text("itemId"),
            attempt = row.attempts.toLong().coerceAtLeast(1),
            errorCode = text("errorCode") ?: "message/outcome-unknown",
            composerOwned = (value["composerOwned"] as? JsonPrimitive)?.booleanOrNull ?: true,
            mode =
                when (text("mode")) {
                    PromptMode.Steer.name -> PromptMode.Steer
                    else -> PromptMode.Queue
                },
            timeZone = text("timeZone"),
            fileReceiptIds =
                (value["fileReceiptIds"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .toPersistentList(),
        )
    }

    private companion object {
        const val KIND = "prompt"
    }
}

/** Builds the prompt outbox over the app database. */
fun createPendingPromptOutbox(context: Context): PendingPromptOutbox =
    PendingPromptOutbox(AppDatabase.get(context.applicationContext).outbox())
