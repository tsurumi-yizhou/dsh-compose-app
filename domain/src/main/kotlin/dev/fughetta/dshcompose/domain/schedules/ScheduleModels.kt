package dev.fughetta.dshcompose.domain.schedules

import java.time.Instant

enum class ScheduleTargetKind {
    Session,
    NewOnce,
    NewEach,
}

data class ScheduleTarget(
    val kind: ScheduleTargetKind = ScheduleTargetKind.NewOnce,
    val sessionId: String? = null,
    val cwd: String? = null,
    val agentPreset: String = "",
)

data class ScheduleTask(
    val id: String,
    val title: String,
    val prompt: String,
    val kind: String,
    val status: String,
    val target: ScheduleTarget,
    val involvedSessionIds: List<String>,
    val updatedAt: String?,
    val scheduledAt: Instant?,
    val lastDeliveredAt: Instant?,
    val timing: ScheduleDraft,
) {
    val active
        get() = status == "active"

    val targetSessionId
        get() = target.sessionId
}

data class SchedulePage(
    val items: List<ScheduleTask>,
    val total: Int,
    val offset: Int,
    val shown: Int,
)

data class ScheduleRun(
    val messageId: String?,
    val scheduledAt: Instant?,
    val deliveredAt: Instant?,
    val prompt: String?,
    val targetSessionId: String?,
)

data class ScheduleHistory(
    val runs: List<ScheduleRun>,
    val nextBefore: String?,
    val pruned: Boolean,
    val unavailable: Boolean,
    val retentionDays: Long?,
    val retentionRecords: Long?,
)

data class ScheduleInput(
    val title: String,
    val prompt: String,
    val timing: ScheduleDraft,
    val target: ScheduleTarget,
) {
    fun validate(now: Instant, original: ScheduleTask? = null): String? =
        when {
            title.trim().isEmpty() || title.trim().length > 120 -> "title_required"
            prompt.isBlank() -> "prompt_required"
            target.kind == ScheduleTargetKind.Session && target.sessionId.isNullOrBlank() ->
                "session_required"
            original != null && timing == original.timing -> null
            else -> timing.validate(now)
        }
}

data class ScheduleChanges(
    val title: String? = null,
    val prompt: String? = null,
    val timing: ScheduleDraft? = null,
    val target: ScheduleTarget? = null,
    val active: Boolean? = null,
) {
    val empty
        get() =
            title == null && prompt == null && timing == null && target == null && active == null
}

class ScheduleOperationException(
    val code: String,
    val rejected: Boolean = false,
    cause: Throwable? = null,
) : Exception(code, cause)
