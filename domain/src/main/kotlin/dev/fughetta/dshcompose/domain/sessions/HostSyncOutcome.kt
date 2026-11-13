package dev.fughetta.dshcompose.domain.sessions

enum class HostSyncOutcome {
    Completed,
    Retryable,
    Unavailable,
}
