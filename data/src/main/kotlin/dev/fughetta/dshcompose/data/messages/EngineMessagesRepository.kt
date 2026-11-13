package dev.fughetta.dshcompose.data.messages

import dev.fughetta.dshcompose.data.sync.PendingPromptOutbox
import dev.fughetta.dshcompose.domain.OperationFailure
import dev.fughetta.dshcompose.domain.attachments.AttachmentLimits
import dev.fughetta.dshcompose.domain.attachments.ComposerFile
import dev.fughetta.dshcompose.domain.hosts.HostSelection
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.protocol.*
import java.util.UUID
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/**
 * One in-memory owner for handed-off prompts and their attachment spool. All folds are serialized.
 * begin transfers exclusive spool ownership; the composer must detach those files without deleting
 * them. Only user/message proof or an acknowledged queue removal releases that ownership. No disk
 * ledger.
 */
class EngineMessagesRepository(
    private val scope: CoroutineScope,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MessagesRepository {
    private val translations = MessageTranslations()
    override val translation: StateFlow<ReasoningTranslation?> = translations.connection

    internal fun connectTranslations(
        client: ProtocolClient,
        parent: CoroutineScope,
    ): AutoCloseable = translations.connect(client, parent)

    private var delivery: Lazy<PendingPromptDelivery>? = null

    fun configureDelivery(connection: () -> HostSelection, outbox: PendingPromptOutbox) {
        check(delivery == null)
        delivery = lazy { PendingPromptDelivery(scope, connection(), this, outbox) }
    }

    override val delivered: SharedFlow<DeliveredPrompt>
        get() = checkNotNull(delivery).value.delivered

    override fun persist(host: String, home: String) =
        checkNotNull(delivery).value.persist(host, home)

    override suspend fun restore(host: String, home: String?): List<PendingPrompt> =
        checkNotNull(delivery).value.restore(host, home)

    override fun resume() = checkNotNull(delivery).value.resume()

    override suspend fun retryDelivery(target: PendingPromptsOwner, requestId: String) =
        checkNotNull(delivery).value.retry(target, requestId)

    private data class Key(val owner: PendingPromptsOwner, val requestId: String)

    private val guard = Any()
    private val mutable = MutableStateFlow<PersistentMap<String, PendingPrompt>>(persistentMapOf())
    override val state: StateFlow<PersistentMap<String, PendingPrompt>> = mutable.asStateFlow()

    private enum class Resolution {
        Accepted,
        Consumed,
        Withdrawn,
    }

    private val consumed = LinkedHashMap<Key, Resolution>()
    private val spoolOwners = mutableMapOf<String, Map<String, Long>>()
    private val fileEpochs = mutableMapOf<String, Long>()
    private var fileSerial = 0L
    private var closed = false

    private fun remember(key: Key, resolution: Resolution) {
        consumed.remove(key)
        consumed[key] = resolution
        while (consumed.size > 1024) consumed.remove(consumed.keys.first())
    }

    override fun wasConsumed(owner: PendingPromptsOwner, requestId: String): Boolean =
        synchronized(guard) { consumed[Key(owner, requestId)] == Resolution.Consumed }

    override fun begin(
        host: String,
        home: String,
        sessionId: String,
        text: String,
        composerText: String,
        quote: String?,
        files: List<ComposerFile>,
        mode: PromptMode,
        timeZone: String?,
        fileReceiptIds: List<String>,
        composerOwned: Boolean,
    ): PendingPrompt =
        synchronized(guard) {
            check(!closed)
            val owner = PendingPromptsOwner(host, home, sessionId)
            val same = mutable.value.values.filter { it.owner == owner }
            if (same.any { it.state == PendingPromptState.Sending })
                throw OperationFailure("message/send-in-progress")
            if (same.any { it.state == PendingPromptState.Uncertain })
                throw OperationFailure("message/outcome-unknown")
            val retained = mutable.value.values.flatMap { it.files }
            if (
                files.map { it.id }.distinct().size != files.size ||
                    files.any { next -> retained.any { it.id == next.id || it.path == next.path } }
            )
                throw OperationFailure("message/attachment-in-use")
            if (
                mutable.value.size >= 64 ||
                    retained.sumOf { it.bytes } + files.sumOf { it.bytes } >
                        AttachmentLimits.MAX_TOTAL_BYTES ||
                    mutable.value.values.sumOf {
                        it.text.length.toLong() + it.composerText.length + (it.quote?.length ?: 0)
                    } + text.length + composerText.length + (quote?.length ?: 0) > 4L * 1024 * 1024
            )
                throw OperationFailure("message/pending-storage-full")
            if (files.any { !java.io.File(it.path).isFile })
                throw OperationFailure("message/attachment-unavailable")
            val tickets = files.associate { it.path to ++fileSerial }
            fileEpochs.putAll(tickets)
            val value =
                PendingPrompt(
                    UUID.randomUUID().toString(),
                    host,
                    home,
                    sessionId,
                    text,
                    quote,
                    composerText,
                    files.toPersistentList(),
                    composerOwned = composerOwned,
                    mode = mode,
                    timeZone = timeZone,
                    fileReceiptIds = fileReceiptIds.toPersistentList(),
                )
            if (tickets.isNotEmpty()) spoolOwners[value.requestId] = tickets
            mutable.value = mutable.value.putting(value.requestId, value)
            value
        }

    /** Only an explicit Retry action may call this; content, files and requestId are immutable. */
    override fun retry(owner: PendingPromptsOwner, requestId: String): PendingPrompt =
        synchronized(guard) {
            check(!closed)
            consumed[Key(owner, requestId)]?.let {
                throw OperationFailure(
                    when (it) {
                        Resolution.Consumed -> "message/already-delivered"
                        Resolution.Accepted -> "message/already-accepted"
                        Resolution.Withdrawn -> "message/already-withdrawn"
                    },
                )
            }
            val value =
                mutable.value[requestId]?.takeIf { it.owner == owner }
                    ?: throw OperationFailure("message/pending-not-found")
            if (value.state != PendingPromptState.Uncertain)
                throw OperationFailure("message/retry-not-available")
            if (value.files.any { !java.io.File(it.path).isFile })
                throw OperationFailure("message/attachment-unavailable")
            value
                .copy(
                    state = PendingPromptState.Sending,
                    attempt = value.attempt + 1,
                    errorCode = null,
                )
                .also { mutable.value = mutable.value.putting(requestId, it) }
        }

    override fun acknowledged(owner: PendingPromptsOwner, requestId: String): PendingPromptFold =
        synchronized(guard) {
            if (closed) return@synchronized PendingPromptFold.Stale
            consumed[Key(owner, requestId)]?.let {
                return@synchronized when (it) {
                    Resolution.Consumed -> PendingPromptFold.Consumed
                    Resolution.Accepted -> PendingPromptFold.Retained
                    Resolution.Withdrawn -> PendingPromptFold.Withdrawn
                }
            }
            val value =
                mutable.value[requestId]?.takeIf { it.owner == owner }
                    ?: return@synchronized PendingPromptFold.Stale
            if (value.files.isEmpty()) {
                // A definite ACK needs no retained spool or payload. The actual inbox still owns
                // queued text.
                remember(Key(owner, requestId), Resolution.Accepted)
                mutable.value = mutable.value.removing(requestId)
            } else
                mutable.value =
                    mutable.value.putting(
                        requestId,
                        value.copy(state = PendingPromptState.Queued, errorCode = null),
                    )
            PendingPromptFold.Retained
        }

    /**
     * A timeout, cancellation or broken transport cannot prove rejection. Late attempts cannot
     * regress state.
     */
    override fun failed(token: PendingPromptAttempt, error: Exception): PendingPromptFold =
        foldFailure(
            token,
            when (error) {
                is OperationFailure -> error.code
                is HostRemoteException -> error.code
                else -> "message/outcome-unknown"
            },
            rejected = false,
        )

    /**
     * Use only for an explicit negative engine answer, never for a transport-side exception/code.
     */
    override fun rejected(token: PendingPromptAttempt, code: String): PendingPromptFold =
        foldFailure(token, code, rejected = true)

    private fun foldFailure(
        token: PendingPromptAttempt,
        code: String,
        rejected: Boolean,
    ): PendingPromptFold =
        synchronized(guard) {
            if (closed) return@synchronized PendingPromptFold.Stale
            consumed[Key(token.owner, token.requestId)]?.let {
                return@synchronized when (it) {
                    Resolution.Consumed -> PendingPromptFold.Consumed
                    Resolution.Accepted -> PendingPromptFold.Retained
                    Resolution.Withdrawn -> PendingPromptFold.Withdrawn
                }
            }
            val value =
                mutable.value[token.requestId]?.takeIf {
                    it.owner == token.owner && it.attempt == token.number
                } ?: return@synchronized PendingPromptFold.Stale
            if (value.state != PendingPromptState.Sending)
                return@synchronized PendingPromptFold.Retained
            mutable.value =
                mutable.value.putting(
                    token.requestId,
                    value.copy(
                        state =
                            if (rejected) PendingPromptState.Rejected
                            else PendingPromptState.Uncertain,
                        errorCode = code,
                    ),
                )
            PendingPromptFold.Retained
        }

    override fun connectionLost(host: String, home: String) =
        synchronized(guard) {
            if (!closed)
                mutable.value =
                    mutable.value
                        .mapValues { (_, value) ->
                            if (
                                value.host == host &&
                                    value.home == home &&
                                    value.state == PendingPromptState.Sending
                            )
                                value.copy(
                                    state = PendingPromptState.Uncertain,
                                    errorCode = "message/outcome-unknown",
                                )
                            else value
                        }
                        .toPersistentMap()
        }

    fun observeRecord(host: String, home: String, sessionId: String, record: SessionRecord) {
        if (record.type != "user/message") return
        val source = record.data["source"] as? JsonObject ?: return
        if ((source["kind"] as? JsonPrimitive)?.contentOrNull != "user") return
        val id = (source["rpcId"] as? JsonPrimitive)?.contentOrNull ?: return
        val owner = PendingPromptsOwner(host, home, sessionId)
        val released =
            synchronized(guard) {
                if (closed) return
                if (
                    consumed[Key(owner, id)] == Resolution.Withdrawn ||
                        mutable.value[id]?.takeIf { it.owner == owner }?.state ==
                            PendingPromptState.Recovered
                )
                    return@synchronized null
                remember(Key(owner, id), Resolution.Consumed)
                val value =
                    mutable.value[id]?.takeIf { it.owner == owner } ?: return@synchronized null
                mutable.value = mutable.value.removing(id)
                value
            }
        released?.let(::deleteFiles)
    }

    /**
     * Only the current InboxProjection result belongs here; history absence is never a withdrawal.
     */
    fun observeInbox(host: String, home: String, sessionId: String, items: List<InboxItem>) =
        synchronized(guard) {
            if (closed) return@synchronized
            val owner = PendingPromptsOwner(host, home, sessionId)
            var next = mutable.value
            for (item in items) {
                if (item.sourceKind != "user") continue
                val id = item.requestId ?: continue
                if (Key(owner, id) in consumed) continue
                val value = next[id]?.takeIf { it.owner == owner } ?: continue
                next =
                    next.putting(
                        id,
                        value.copy(
                            state = PendingPromptState.Queued,
                            itemId = item.id,
                            errorCode = null,
                        ),
                    )
            }
            mutable.value = next
        }

    override fun retainedFileIds(): Set<String> =
        synchronized(guard) {
            mutable.value.values.flatMap { it.files }.mapTo(mutableSetOf()) { it.id }
        }

    override fun find(owner: PendingPromptsOwner, requestId: String): PendingPrompt? =
        synchronized(guard) { mutable.value[requestId]?.takeIf { it.owner == owner } }

    override fun restore(prompt: PendingPrompt): Boolean =
        synchronized(guard) {
            if (closed) return@synchronized false
            if (prompt.text.isBlank() || prompt.sessionId.isBlank()) return@synchronized false
            if (mutable.value.containsKey(prompt.requestId)) return@synchronized false
            if (consumed.containsKey(Key(prompt.owner, prompt.requestId))) return@synchronized false
            mutable.value = mutable.value.put(prompt.requestId, prompt)
            true
        }

    override fun byItem(owner: PendingPromptsOwner, itemId: String): PendingPrompt? =
        synchronized(guard) {
            mutable.value.values.firstOrNull {
                it.owner == owner && it.itemId == itemId && it.state != PendingPromptState.Recovered
            }
        }

    /**
     * Called after updateQueue(Remove) ACK. Restoration retains the spool until recoverDraft
     * transfers it to the composer; removal releases it.
     */
    override fun withdrawn(
        owner: PendingPromptsOwner,
        itemId: String,
        restore: Boolean,
        content: RecoveredPromptContent?,
    ): PendingPrompt? {
        val value =
            synchronized(guard) {
                if (closed) return null
                val found =
                    mutable.value.values.firstOrNull {
                        it.owner == owner &&
                            it.itemId == itemId &&
                            it.state != PendingPromptState.Recovered
                    } ?: return null
                remember(Key(owner, found.requestId), Resolution.Withdrawn)
                if (restore)
                    found
                        .copy(
                            state = PendingPromptState.Recovered,
                            itemId = null,
                            composerText = content?.text ?: found.composerText,
                            quote = if (content == null) found.quote else content.quote,
                            errorCode = "message/recovered",
                        )
                        .also { mutable.value = mutable.value.putting(it.requestId, it) }
                else found.also { mutable.value = mutable.value.removing(it.requestId) }
            }
        if (!restore) deleteFiles(value)
        return value
    }

    override fun retainWithdrawnText(
        owner: PendingPromptsOwner,
        rawText: String,
        content: RecoveredPromptContent,
    ): PendingPrompt =
        synchronized(guard) {
            check(!closed)
            PendingPrompt(
                    UUID.randomUUID().toString(),
                    owner.host,
                    owner.home,
                    owner.sessionId,
                    rawText,
                    content.quote,
                    content.text,
                    persistentListOf(),
                    state = PendingPromptState.Recovered,
                    errorCode = "message/recovered",
                )
                .also { mutable.value = mutable.value.putting(it.requestId, it) }
        }

    override fun recoverDraft(owner: PendingPromptsOwner, requestId: String): PendingPrompt? =
        synchronized(guard) {
            if (closed) return@synchronized null
            val value =
                mutable.value[requestId]?.takeIf {
                    it.owner == owner &&
                        it.state in setOf(PendingPromptState.Rejected, PendingPromptState.Recovered)
                } ?: return@synchronized null
            value.files.forEach { fileEpochs.remove(it.path) }
            spoolOwners.remove(requestId)
            mutable.value = mutable.value.removing(requestId)
            value
        }

    override fun sessionDeleted(owner: PendingPromptsOwner) {
        val released =
            synchronized(guard) {
                if (closed) return
                val values = mutable.value.values.filter { it.owner == owner }
                values.forEach {
                    remember(Key(owner, it.requestId), Resolution.Withdrawn)
                    mutable.value = mutable.value.removing(it.requestId)
                }
                values
            }
        released.forEach(::deleteFiles)
    }

    private fun deleteFiles(value: PendingPrompt) {
        val tickets = synchronized(guard) { spoolOwners.remove(value.requestId).orEmpty() }
        scope.launch(cleanupDispatcher) {
            synchronized(guard) {
                value.files
                    .filter { candidate ->
                        !closed &&
                            fileEpochs[candidate.path] == tickets[candidate.path] &&
                            mutable.value.values.none { pending ->
                                pending.files.any {
                                    it.id == candidate.id || it.path == candidate.path
                                }
                            }
                    }
                    .forEach { candidate ->
                        java.io.File(candidate.path).delete()
                        fileEpochs.remove(candidate.path)
                    }
            }
        }
    }

    override fun close() =
        synchronized(guard) {
            closed = true
            translations.close()
            delivery?.takeIf { it.isInitialized() }?.value?.close()
            consumed.clear()
            fileEpochs.clear()
            spoolOwners.clear()
            mutable.value = persistentMapOf()
            // EngineAttachmentsRepository owns final directory cleanup. Host switches never clear
            // this
            // ledger.
        }
}
