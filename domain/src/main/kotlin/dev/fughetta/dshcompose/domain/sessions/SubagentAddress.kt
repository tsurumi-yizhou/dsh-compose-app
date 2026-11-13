package dev.fughetta.dshcompose.domain.sessions

enum class SubagentMode(val wire: String) {
    OneShot("one-shot"),
    Continuable("continuable"),
    External("external"),
    Unknown("unknown");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

data class SubagentAddress(
    val parentSessionId: String,
    val childSessionId: String,
    val mode: SubagentMode = SubagentMode.Unknown,
)
