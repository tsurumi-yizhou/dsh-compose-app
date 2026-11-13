package dev.fughetta.dshcompose.domain.messages

enum class InboxSlot {
    NextStep,
    NextTurn,
}

private val inboxContextSources =
    setOf(
        "user-approval",
        "plan-mode",
        "working-directory",
        "cordis-host-runner",
        "subagent-settled",
        "agent-message",
        "team-message",
        "goal",
        "schedule",
        "user-question-reply",
        "tool-goal",
        "ptc-mode",
    )

data class InboxItem(
    val id: String,
    val slot: InboxSlot,
    val text: String,
    val requestId: String?,
    val sourceKind: String,
    val sourceForm: String?,
    val textOnly: Boolean,
) {
    val hidden
        get() = sourceKind in inboxContextSources

    val automatic
        get() = hidden || sourceKind == "tool-jobs"
}
