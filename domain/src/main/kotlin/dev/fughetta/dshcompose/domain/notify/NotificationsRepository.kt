package dev.fughetta.dshcompose.domain.notify

import dev.fughetta.dshcompose.domain.chat.ToolPayload
import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration
import dev.fughetta.dshcompose.domain.messages.QuestionAnswer
import dev.fughetta.dshcompose.domain.sessions.SessionSummary

/** Bounded tail of one finished turn, for notification text. */
data class NotificationTurn(
    val seq: Long,
    val kind: String,
    val title: String?,
    val reply: String?,
    val code: String?,
)

/** One tool call recovered for a notification, already privacy-filtered. */
data class NotificationCall(val name: String, val exactText: String)

/**
 * Connection-bound notification reads, pending interactions, and acknowledged host responses.
 * Completion reads use one bounded tail page, never a session watch or a history walk.
 */
interface NotificationsRepository : AutoCloseable {
    val repository: NotificationsRepository?
    val running: Map<String, NotificationRun>
    val changes: Map<String, Long>
    val subagents: Set<String>
    val titles: Map<String, String>
    val seen: Map<String, Long>

    fun current(generation: ConnectionGeneration): Boolean

    fun connect(repository: NotificationsRepository?)

    fun sessions(rows: List<SessionSummary>)

    fun event(value: ToolPayload.Obj)

    fun root(id: String): String

    suspend fun awaitMetadata()

    fun reading(id: String, active: Boolean)

    fun recordSeen(id: String, sequence: Long)

    fun rememberTitle(id: String, title: String)

    val generation: ConnectionGeneration?
    val active: Boolean
    val pendingRequests: Map<String, ToolPayload.Obj>

    suspend fun sessions(): List<SessionSummary>

    suspend fun cancel(sessionId: String)

    suspend fun respondApproval(eventId: String, decision: String)

    fun question(eventId: String): dev.fughetta.dshcompose.domain.messages.UserQuestionRequest?

    suspend fun answerQuestion(eventId: String, answers: List<QuestionAnswer>)

    suspend fun rejectQuestion(eventId: String)

    suspend fun turn(sessionId: String): NotificationTurn?

    suspend fun call(sessionId: String, callId: String, toolName: String): NotificationCall?
}
