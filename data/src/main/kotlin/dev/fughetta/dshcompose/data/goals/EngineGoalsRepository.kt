package dev.fughetta.dshcompose.data.goals

import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.hosts.ConnectionState
import dev.fughetta.dshcompose.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns goal reconciliation across durable transcript revisions and live activation events. */
class EngineGoalsRepository : GoalsRepository, AutoCloseable {
    private var source: ProtocolClient? = null
    private var scope: CoroutineScope? = null
    private val gate = Any()
    private var generation: ConnectionGeneration? = null
    private val mutable = MutableStateFlow<GoalState?>(null)
    override val state = mutable.asStateFlow()
    private var sessionId: String? = null
    private var visibleSession: String? = null
    private var value: GoalState? = null
    private var epoch = 0L
    private var readEpoch = 0L
    private var eventEpoch = 0L
    private var read: Job? = null
    private var mutation: Job? = null
    private var attempted: GoalRef? = null
    private var logged: GoalProjection? = null
    private var ahead: GoalView? = null
    private var cleared: GoalRef? = null
    private var activation: Pair<GoalRef, GoalActivation?>? = null
    private var running = false

    private val ProtocolClient.active: Boolean
        get() =
            generation != null &&
                mux.state.value.generation == generation &&
                mux.state.value.state == ConnectionState.Connected

    fun connect(value: ProtocolClient?, parent: CoroutineScope) =
        synchronized(gate) {
            val nextGeneration = value?.mux?.state?.value?.generation
            if (source === value && generation == nextGeneration && source?.active == true)
                return@synchronized
            generation = nextGeneration
            reset()
            scope?.cancel()
            source = value
            val next = value?.let {
                CoroutineScope(
                    parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]),
                )
            }
            scope = next
            if (value != null && next != null)
                next.launch {
                    value.mux.hostEvents.mapNotNull(::goalActivationChange).collect { change ->
                        synchronized(gate) {
                            if (scope === next && source === value && value.active)
                                activation(change)
                        }
                    }
                }
        }

    fun session(id: String?) {
        synchronized(gate) {
            if (sessionId == id) return
            reset()
            sessionId = id
            value = id?.let {
                GoalState(it, canRead = source?.connection?.supportsGoalRead == true, owner = epoch)
            }
            emit()
        }
    }

    private fun reset() {
        epoch++
        readEpoch++
        eventEpoch++
        read?.cancel()
        mutation?.cancel()
        attempted = null
        logged = null
        ahead = null
        cleared = null
        activation = null
        sessionId = null
        value = null
        running = false
        mutable.value = null
    }

    override fun observe(id: String, value: Boolean) {
        synchronized(gate) {
            if (value) visibleSession = id else if (visibleSession == id) visibleSession = null
            if (sessionId != id) return
            if (value) ensureRead(force = true)
            else {
                readEpoch++
                read?.cancel()
                attempted = null
            }
        }
    }

    override fun transcript(
        id: String,
        projection: GoalProjection?,
        isRunning: Boolean,
        ready: Boolean,
    ) {
        synchronized(gate) {
            if (sessionId != id) return
            val changed = logged != projection
            val runChanged = running != isRunning
            val wasReady = value?.ready == true
            running = isRunning
            if (changed) {
                logged = projection
                ahead?.let { view ->
                    if (
                        projection == null ||
                            projection.goal.ref.id != view.ref.id ||
                            projection.goal.ref.revision >= view.ref.revision
                    )
                        ahead = null
                }
                cleared?.let { tombstone ->
                    if (
                        projection == null ||
                            projection.goal.ref.id != tombstone.id ||
                            projection.goal.ref.revision >= tombstone.revision
                    )
                        cleared = null
                }
                attempted = null
            }
            if (changed || runChanged || ready != wasReady) {
                value = value?.copy(ready = ready && source?.active == true)
                emit()
                ensureRead(force = runChanged || ready && !wasReady)
            }
        }
    }

    private fun projection(): GoalProjection? {
        val base = logged ?: return null
        val projected =
            ahead
                ?.takeIf {
                    it.ref.id == base.goal.ref.id && it.ref.revision > base.goal.ref.revision
                }
                ?.projection ?: base
        return projected.takeUnless { current ->
            cleared?.let {
                it.id == current.goal.ref.id && current.goal.ref.revision < it.revision
            } == true
        }
    }

    private fun emit() {
        val current = projection()
        val known = activation?.takeIf { it.first == current?.goal?.ref }?.second
        value =
            value?.copy(
                projection = current,
                activation = known,
            )
        mutable.value = value
    }

    internal fun activation(change: GoalActivationChange) {
        synchronized(gate) {
            if (change.sessionId != sessionId) return
            val current = projection()?.goal?.ref
            val ref = change.ref
            if (
                ref != null &&
                    current != null &&
                    (ref.id == current.id && ref.revision < current.revision)
            )
                return
            eventEpoch++
            readEpoch++
            read?.cancel()
            activation = ref?.let { it to change.activation }
            emit()
        }
    }

    override fun refresh() {
        synchronized(gate) { ensureRead(force = true) }
    }

    private fun ensureRead(force: Boolean = false) {
        val owner = source ?: return
        val id = sessionId ?: return
        val current = projection()?.goal ?: return
        if (
            visibleSession != id ||
                value?.ready != true ||
                !owner.connection.supportsGoalRead ||
                !owner.active ||
                current.phase != GoalPhase.Active
        )
            return
        if (
            !force &&
                (attempted == current.ref ||
                    activation?.let { it.first == current.ref && it.second != null } == true)
        )
            return
        if (force) activation = null
        attempted = current.ref
        val own = epoch
        val revision = ++readEpoch
        val events = eventEpoch
        read?.cancel()
        emit()
        read = scope?.launch {
            try {
                val result = withTimeout(20000) { owner.goal(id) }
                synchronized(gate) {
                    if (
                        current(owner, own) &&
                            revision == readEpoch &&
                            events == eventEpoch &&
                            projection()?.goal?.ref == current.ref &&
                            result?.ref == current.ref
                    ) {
                        activation = result.ref to result.activation
                        value = value?.copy(error = null)
                        emit()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                synchronized(gate) {
                    if (current(owner, own) && revision == readEpoch) failedRead()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                synchronized(gate) {
                    if (current(owner, own) && revision == readEpoch) failedRead()
                }
            }
        }
    }

    private fun failedRead() {
        value = value?.copy(error = "GOAL_READ_FAILED")
        emit()
    }

    private data class Answer(val view: GoalView? = null, val tombstone: GoalRef? = null)

    private data class Request(
        val source: ProtocolClient,
        val epoch: Long,
        val events: Long,
        val task: Deferred<Answer>,
    )

    override suspend fun edit(owner: Long, ref: GoalRef, objective: String) =
        mutate(owner, ref) { owner, id ->
            Answer(view = owner.editGoal(id, ref, objective = objective.trim()))
        }

    override suspend fun change(owner: Long, ref: GoalRef, action: GoalAction) {
        if (action == GoalAction.Clear) return
        mutate(owner, ref) { owner, id -> Answer(view = owner.changeGoal(id, ref, action)) }
    }

    override suspend fun clear(owner: Long, ref: GoalRef) =
        mutate(owner, ref) { owner, id ->
            val result = owner.clearGoal(id, ref)
            if (result.id != ref.id || result.revision <= ref.revision)
                throw GoalOperationException("GOAL_CHANGE_FAILED")
            Answer(tombstone = result)
        }

    private suspend fun mutate(
        ownerEpoch: Long,
        ref: GoalRef,
        operation: suspend (ProtocolClient, String) -> Answer,
    ) {
        val request =
            synchronized(gate) {
                if (ownerEpoch != epoch) return
                val owner = source ?: return
                val id = sessionId ?: return
                val parent = scope ?: return
                if (!owner.active || value?.busy == true || value?.ready != true) return
                readEpoch++
                read?.cancel()
                value = value?.copy(busy = true, error = null)
                emit()
                Request(
                        owner,
                        epoch,
                        eventEpoch,
                        parent.async { withTimeout(65_000) { operation(owner, id) } },
                    )
                    .also { mutation = it.task }
            }
        try {
            val answer = request.task.await()
            synchronized(gate) {
                if (!current(request.source, request.epoch)) return@synchronized
                answer.tombstone?.let { cleared = it }
                answer.view?.let { result ->
                    if (result.ref.id != ref.id) throw GoalOperationException("GOAL_CHANGE_FAILED")
                    ahead = result
                    val known = activation
                    if (
                        projection()?.goal?.ref == result.ref &&
                            (known == null ||
                                known.first.id != result.ref.id ||
                                known.first.revision < result.ref.revision ||
                                known.first == result.ref && request.events == eventEpoch)
                    )
                        activation = result.ref to result.activation
                }
                value = value?.copy(mutationRevision = (value?.mutationRevision ?: 0) + 1)
                emit()
            }
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            synchronized(gate) {
                if (current(request.source, request.epoch)) {
                    value = value?.copy(error = "GOAL_CHANGE_FAILED")
                    emit()
                }
            }
        } catch (error: Exception) {
            synchronized(gate) {
                if (current(request.source, request.epoch)) {
                    value =
                        value?.copy(
                            error =
                                when (error) {
                                    is GoalOperationException -> error.code
                                    is HostRemoteException -> error.code
                                    else -> "GOAL_CHANGE_FAILED"
                                },
                        )
                    emit()
                }
            }
        } finally {
            request.task.cancel()
            synchronized(gate) {
                if (current(request.source, request.epoch)) {
                    value = value?.copy(busy = false)
                    emit()
                }
            }
        }
    }

    private fun current(owner: ProtocolClient, own: Long) =
        source === owner && epoch == own && sessionId != null && owner.active

    override fun close() =
        synchronized(gate) {
            reset()
            scope?.cancel()
            scope = null
            source = null
            generation = null
            visibleSession = null
        }
}
