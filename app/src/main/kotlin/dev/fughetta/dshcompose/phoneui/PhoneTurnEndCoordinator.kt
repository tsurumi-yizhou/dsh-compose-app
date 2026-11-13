package dev.fughetta.dshcompose.phoneui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.fughetta.dshcompose.phone.PhoneControl
import dev.fughetta.dshcompose.phone.PhoneTaskReturn
import kotlin.coroutines.resume
import kotlinx.coroutines.*

data class PhoneTurnFinish(
    val closed: Boolean,
    val busy: Boolean,
    val opened: Boolean,
    val launched: Boolean,
    val notified: Boolean,
    val current: Boolean,
)

/** Native tasks have no conversation ID. Only their initial, unambiguous local run owns an end. */
class PhoneTurnEndCoordinator(
    context: Context,
    parentScope: CoroutineScope,
    private val localOwner: () -> Any?,
    private val runningLocalSessions: suspend () -> Set<String>,
    private val returnAfterDone: () -> Boolean,
    private val revealSession: (String) -> Unit,
    private val cancelLocalTurns: suspend () -> Unit = {},
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val control = PhoneControl.get(context.applicationContext)
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val main = Handler(Looper.getMainLooper())
    private var taskId: Long? = null
    private var armed = false
    private var candidates: Set<String>? = null
    private var owner: Any? = null
    private var reading: Job? = null
    private var epoch = 0L
    private var closed = false
    private var waitingEnds = 0
    private var stopped: Boolean? = null
    private val sink: (Map<String, Any?>) -> Unit = sink@{ status ->
        if (closed) return@sink
        val task = status["task"] as? Map<*, *> ?: return@sink
        val nowStopped = task["stopped"] == true
        if (nowStopped && stopped == false) {
            val stoppedOwner = localOwner()
            scope.launch {
                try {
                    if (!closed && stoppedOwner != null && localOwner() === stoppedOwner)
                        cancelLocalTurns()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!closed) onError("phone/cancel-local-turns")
                }
            }
        }
        stopped = nowStopped
        val id = (task["id"] as? Number)?.toLong()
        if (task["active"] == true && id != null && taskId != id) {
            reading?.cancel()
            epoch++
            taskId = id
            armed = true
            candidates = null
            owner = null
            capture()
        } else if (armed && task["active"] != true && task["stopped"] != true && id == taskId) {
            reading?.cancel()
            epoch++
            armed = false
            candidates = null
            owner = null
        }
    }

    init {
        control.addSink(sink)
    }

    /** Call whenever the local mux connection generation changes, including disconnect. */
    fun localConnectionChanged() {
        if (closed) return
        val current = localOwner()
        if (owner == null && armed && candidates == null && reading == null && current != null)
            capture()
        else if (owner !== current) invalidate()
    }

    /** A newer run revokes an earlier end that was waiting for task ownership. */
    fun onLocalTurnStarted(sessionId: String) {
        if (
            waitingEnds > 0 && reading != null ||
                !armed && owner != null && candidates?.contains(sessionId) != false
        )
            invalidate()
    }

    private fun capture() {
        val capturedOwner = localOwner() ?: return
        val capturedTask = taskId ?: return
        val capturedEpoch = epoch
        owner = capturedOwner
        reading = scope.launch {
            try {
                val running = withTimeout(5_000) { runningLocalSessions() }.toSet()
                if (
                    !closed &&
                        epoch == capturedEpoch &&
                        armed &&
                        taskId == capturedTask &&
                        localOwner() === capturedOwner
                )
                    candidates = running
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
            } catch (_: Exception) {
                /* Missing ownership evidence never authorizes an automatic return. */
            } finally {
                if (epoch == capturedEpoch) reading = null
            }
        }
    }

    suspend fun onLocalTurnEnd(
        sessionId: String,
        host: String? = null,
        bot: Boolean = false,
    ): PhoneTurnFinish? {
        if (closed || !armed || waitingEnds >= 8) return null
        val capturedTask = taskId ?: return null
        val capturedEpoch = epoch
        val capturedOwner = owner ?: return null
        if (capturedOwner !== localOwner()) {
            invalidate()
            return null
        }
        waitingEnds++
        try {
            reading?.join()
        } finally {
            waitingEnds--
        }
        if (
            closed ||
                capturedEpoch != epoch ||
                taskId != capturedTask ||
                !armed ||
                localOwner() !== capturedOwner
        )
            return null
        val capturedCandidates = candidates ?: return null
        if (capturedCandidates.size != 1 || sessionId !in capturedCandidates) return null
        armed = false
        candidates = null
        val finished =
            withContext(Dispatchers.IO) {
                if (closed || epoch != capturedEpoch || localOwner() !== capturedOwner) null
                else control.finishTaskScope(capturedTask)
            }
        val busy = finished == null && control.tasks.callsInFlight > 0
        val waitingOwner = finished ?: control.tasks.activeOwner(capturedTask)
        val reopen = returnAfterDone() && !control.prompts.appResumed()
        val result =
            suspendCancellableCoroutine<PhoneTurnFinish> { continuation ->
                val callbacks = Any()
                continuation.invokeOnCancellation { main.removeCallbacksAndMessages(callbacks) }
                fun current() =
                    continuation.isActive &&
                        !closed &&
                        epoch == capturedEpoch &&
                        taskId == capturedTask &&
                        localOwner() === capturedOwner &&
                        waitingOwner != null &&
                        control.tasks.isCurrent(waitingOwner)
                val action = Runnable {
                    if (!continuation.isActive) return@Runnable
                    PhoneTaskReturn(
                            current = ::current,
                            visible = control.prompts::appResumed,
                            launch = { control.reopen(if (bot) null else sessionId, host) },
                            notify = {
                                control.notificationGate.notifyFinished(
                                    capturedTask,
                                    if (bot) null else sessionId,
                                    host,
                                )
                            },
                            schedule = { delay, block ->
                                main.postAtTime(
                                    block,
                                    callbacks,
                                    SystemClock.uptimeMillis() + delay,
                                )
                            },
                            clock = SystemClock::elapsedRealtime,
                        )
                        .start(finished != null, busy, reopen) { value ->
                            main.removeCallbacksAndMessages(callbacks)
                            if (continuation.isActive)
                                continuation.resume(
                                    PhoneTurnFinish(
                                        value["closed"] == true,
                                        value["busy"] == true,
                                        value["opened"] == true,
                                        value["launched"] == true,
                                        value["notified"] == true,
                                        value["current"] == true,
                                    ),
                                )
                        }
                }
                main.postAtTime(action, callbacks, SystemClock.uptimeMillis())
            }
        if (
            closed ||
                epoch != capturedEpoch ||
                taskId != capturedTask ||
                localOwner() !== capturedOwner
        )
            return result.copy(current = false)
        if (result.busy && result.current && !armed) {
            armed = true
            candidates = capturedCandidates
        }
        if (result.current && result.opened && result.launched && !bot) revealSession(sessionId)
        return result
    }

    private fun invalidate() {
        reading?.cancel()
        reading = null
        epoch++
        armed = false
        candidates = null
        owner = null
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        control.removeSink(sink)
        lifetime.cancel()
    }
}
