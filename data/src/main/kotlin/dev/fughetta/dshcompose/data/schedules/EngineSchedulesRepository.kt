package dev.fughetta.dshcompose.data.schedules

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.domain.schedules.*
import dev.fughetta.dshcompose.domain.schedules.ScheduleHistory
import dev.fughetta.dshcompose.domain.schedules.SchedulePage
import dev.fughetta.dshcompose.domain.schedules.ScheduleRun
import dev.fughetta.dshcompose.domain.schedules.ScheduleTask
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Publishes connection-bound operations; the connection scope owns requests and cached reads. */
class EngineSchedulesRepository(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) :
    AutoCloseable {
    private val mutable = MutableStateFlow<SchedulesRepository?>(null)
    val connection = mutable.asStateFlow()
    private var owner: ConnectedRepository? = null
    private var client: ProtocolClient? = null
    private var generation: ConnectionGeneration? = null

    fun connect(client: ProtocolClient?, scope: CoroutineScope) {
        val generation = client?.mux?.state?.value?.generation
        if (this.client === client && this.generation == generation && owner?.active == true) return
        this.client = client
        this.generation = generation
        owner?.close()
        owner =
            client
                ?.takeIf {
                    generation != null && it.mux.state.value.state == ConnectionState.Connected
                }
                ?.let { ConnectedRepository(it, scope, dispatcher) }
        mutable.value = owner
    }

    override fun close() {
        owner?.close()
        owner = null
        client = null
        generation = null
        mutable.value = null
    }

    private class ConnectedRepository(
        private val client: ProtocolClient,
        parent: CoroutineScope,
        private val dispatcher: CoroutineDispatcher,
    ) : SchedulesRepository {
        private val scope =
            CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val generation = client.mux.state.value.generation
        override val active
            get() =
                scope.isActive &&
                    generation != null &&
                    client.mux.state.value.generation == generation &&
                    client.mux.state.value.state == ConnectionState.Connected

        override val canManage
            get() = client.connection.supportsScheduleManagement

        private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val changes = notifications.asSharedFlow()
        private val lock = Any()
        private var catalog: Deferred<List<ScheduleTask>>? = null

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.mux.hostEvents
                    .filter {
                        (it["type"] as? JsonPrimitive)?.contentOrNull == "emit" &&
                            (it["event"] as? JsonPrimitive)?.contentOrNull == "schedule/changed"
                    }
                    .collect {
                        invalidate()
                        notifications.emit(Unit)
                    }
            }
        }

        fun close() {
            scope.cancel()
        }

        override fun invalidate() =
            synchronized(lock) {
                catalog?.cancel()
                catalog = null
            }

        private suspend fun <T> guarded(block: suspend () -> T): T {
            if (!active) throw CancellationException("Schedule connection retired")
            try {
                return block().also {
                    if (!active) throw CancellationException("Schedule connection retired")
                }
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive || !active) throw error
                throw ScheduleOperationException("request_failed", cause = error)
            } catch (error: HostRemoteException) {
                throw ScheduleOperationException(error.code, rejected = true, cause = error)
            } catch (error: ScheduleOperationException) {
                throw error
            } catch (error: Exception) {
                throw ScheduleOperationException("request_failed", cause = error)
            }
        }

        private suspend fun <T> request(block: suspend () -> T): T {
            val task = scope.async(dispatcher) { guarded(block) }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }

        private suspend fun readPage(active: Boolean?, offset: Int): SchedulePage =
            client.listSchedules(active, offset).let {
                SchedulePage(it.items.map { task -> task.domain() }, it.total, it.offset, it.shown)
            }

        private suspend fun readHistory(id: String, before: String?): ScheduleHistory =
            client.scheduleHistory(id, before).let {
                ScheduleHistory(
                    it.runs.map { run ->
                        ScheduleRun(
                            run.messageId,
                            run.scheduledAt,
                            run.deliveredAt,
                            run.prompt,
                            run.targetSessionId,
                        )
                    },
                    it.nextBefore,
                    it.pruned,
                    it.unavailable,
                    it.retentionDays,
                    it.retentionRecords,
                )
            }

        private suspend fun createTask(input: ScheduleInput) =
            client
                .createSchedule(
                    input.title,
                    input.prompt,
                    input.timing.toWire(),
                    input.target.toWire(),
                )
                .domain()

        private suspend fun editTask(task: ScheduleTask, changes: ScheduleChanges) =
            client
                .editSchedule(
                    task.id,
                    task.updatedAt,
                    buildJsonObject {
                        changes.title?.let { put("title", it) }
                        changes.prompt?.let { put("prompt", it) }
                        changes.timing?.let { put("timing", it.toWire()) }
                        changes.target?.let { put("target", it.toWire()) }
                        changes.active?.let { put("active", it) }
                    },
                )
                .domain()

        private suspend fun deleteTask(task: ScheduleTask) {
            if (!client.deleteSchedule(task.id, task.updatedAt))
                throw ScheduleOperationException("schedule_not_found", rejected = true)
        }

        override suspend fun list(active: Boolean?, offset: Int) = request {
            readPage(active, offset)
        }

        override suspend fun history(id: String, before: String?) = request {
            readHistory(id, before)
        }

        override suspend fun catalog(): List<ScheduleTask> {
            if (!active) throw CancellationException("Schedule connection retired")
            val read =
                synchronized(lock) {
                    catalog
                        ?: scope
                            .async(dispatcher) {
                                guarded { client.scheduleCatalog().map { it.domain() } }
                            }
                            .also { catalog = it }
                }
            return try {
                read.await().also {
                    if (!active) throw CancellationException("Schedule connection retired")
                }
            } catch (error: Exception) {
                if (read.isCompleted) synchronized(lock) { if (catalog === read) catalog = null }
                throw error
            }
        }

        override suspend fun create(input: ScheduleInput) = request {
            createTask(input).also { invalidate() }
        }

        override suspend fun update(task: ScheduleTask, changes: ScheduleChanges) = request {
            editTask(task, changes).also { invalidate() }
        }

        override suspend fun delete(task: ScheduleTask) = request {
            deleteTask(task).also { invalidate() }
        }

        override suspend fun run(task: ScheduleTask) = request {
            client.runSchedule(task.id).also { invalidate() }
        }
    }
}

private fun ScheduleTarget.toWire() = buildJsonObject {
    put(
        "kind",
        when (kind) {
            ScheduleTargetKind.Session -> "session"
            ScheduleTargetKind.NewOnce -> "new_once"
            ScheduleTargetKind.NewEach -> "new"
        },
    )
    if (kind == ScheduleTargetKind.Session) put("sessionId", sessionId!!)
    else if (cwd != null || agentPreset.isNotBlank())
        putJsonObject("create") {
            cwd?.let { put("cwd", it) }
            agentPreset.trim().takeIf { it.isNotEmpty() }?.let { put("agentPreset", it) }
        }
}

private fun dev.fughetta.dshcompose.protocol.ScheduleTask.domain(): ScheduleTask {
    fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    val create = target["create"] as? JsonObject
    return ScheduleTask(
        id,
        title,
        prompt,
        kind,
        status,
        ScheduleTarget(
            when (target.text("kind")) {
                "session" -> ScheduleTargetKind.Session
                "new_once" -> ScheduleTargetKind.NewOnce
                else -> ScheduleTargetKind.NewEach
            },
            target.text("sessionId"),
            create.text("cwd"),
            create.text("agentPreset").orEmpty(),
        ),
        involvedSessionIds,
        updatedAt,
        scheduledAt,
        lastDeliveredAt,
        wireTiming(this),
    )
}
