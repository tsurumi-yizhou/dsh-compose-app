package dev.fughetta.dshcompose.domain.notify

import dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration

class NotificationRun(val since: Long, val revision: Long)

class NotificationSessionCallbacks(
    val selected: () -> Boolean,
    val needed: () -> Set<String>,
    val onEnd: (String, Long, ConnectionGeneration) -> Unit,
    val onRunning: (String) -> Unit,
    val onRemoved: (String) -> Unit,
    val onChanged: () -> Unit,
    val onError: (String) -> Unit,
)
