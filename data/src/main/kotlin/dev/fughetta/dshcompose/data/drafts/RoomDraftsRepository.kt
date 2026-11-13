package dev.fughetta.dshcompose.data.drafts

import android.content.Context
import dev.fughetta.dshcompose.data.database.AppDatabase
import dev.fughetta.dshcompose.data.database.DraftRow
import dev.fughetta.dshcompose.domain.drafts.DraftsRepository
import dev.fughetta.dshcompose.domain.drafts.DraftsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun createDraftsRepository(context: Context, applicationScope: CoroutineScope): DraftsRepository {
    val dao = AppDatabase.get(context).drafts()
    return RoomDraftsRepository(
        load = { withContext(Dispatchers.IO) { dao.load() } },
        write = { row ->
            withContext(Dispatchers.IO) { dao.save(row.session, row.text, row.updatedAt) }
        },
        applicationScope = applicationScope,
    )
}

/** Application-owned writes survive the screen that submitted them. */
internal class RoomDraftsRepository(
    private val load: suspend () -> List<DraftRow>,
    private val write: suspend (DraftRow) -> Unit,
    applicationScope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : DraftsRepository {
    private val lock = Any()
    private val drafts = mutableMapOf<String, DraftRow>()
    private val touched = mutableSetOf<String>()
    private val pending = linkedMapOf<String, DraftRow>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(DraftsState())
    override val state = mutable.asStateFlow()

    init {
        applicationScope.launch {
            try {
                val rows = load()
                synchronized(lock) {
                    for (row in rows) if (row.session !in touched) drafts[row.session] = row
                    touched.clear()
                    publish(loaded = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                synchronized(lock) {
                    touched.clear()
                    publish(loaded = true, failed = true)
                }
            }
            for (ignored in changes) {
                // Bound the typing queue to one pending value per conversation.
                delay(400)
                val batch = synchronized(lock) { pending.values.toList().also { pending.clear() } }
                for (row in batch) {
                    try {
                        write(row)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        synchronized(lock) { publish(failed = true) }
                    }
                }
            }
        }
    }

    override suspend fun save(sessionId: String, text: String) {
        synchronized(lock) {
            if ((drafts[sessionId]?.text ?: "") == text) return
            enqueue(sessionId, text)
        }
    }

    override suspend fun forget(sessionId: String) {
        synchronized(lock) { enqueue(sessionId, "") }
    }

    private fun enqueue(sessionId: String, text: String) {
        if (!mutable.value.loaded) touched.add(sessionId)
        val row = DraftRow(sessionId, text, now())
        if (text.isBlank()) drafts.remove(sessionId) else drafts[sessionId] = row
        pending[sessionId] = row
        publish()
        check(changes.trySend(Unit).isSuccess) { "drafts/writer-unavailable" }
    }

    private fun publish(loaded: Boolean = mutable.value.loaded, failed: Boolean = false) {
        drafts.values
            .sortedWith(
                compareByDescending<DraftRow> { it.updatedAt }.thenByDescending { it.session },
            )
            .drop(30)
            .forEach { drafts.remove(it.session) }
        mutable.value =
            DraftsState(
                values = drafts.mapValues { it.value.text },
                loaded = loaded,
                storageErrorRevision = mutable.value.storageErrorRevision + if (failed) 1 else 0,
            )
    }
}
