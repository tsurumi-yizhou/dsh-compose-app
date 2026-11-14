package dev.fughetta.dshcompose.ui

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.goals.*
import dev.fughetta.dshcompose.domain.presets.*
import dev.fughetta.dshcompose.domain.providers.*
import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.domain.sessions.search.*
import kotlinx.collections.immutable.*

enum class RuntimePhase {
    NotInstalled,
    Installing,
    Installed,
    Starting,
    Running,
    Stopping,
    Error,
}

data class RuntimeUiState(
    val phase: RuntimePhase = RuntimePhase.NotInstalled,
    val progress: Float? = null,
    val error: String? = null,
    val logs: List<String> = emptyList(),
)
