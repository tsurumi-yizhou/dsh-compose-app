package dev.fughetta.dshcompose.ui.commands

import dev.fughetta.dshcompose.domain.commands.*
import dev.fughetta.dshcompose.domain.sessions.SessionsRepository
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SlashResolution(
    val command: SlashCommand,
    val line: String,
    val picked: Boolean,
    internal val owner: SessionsRepository,
    internal val sessionId: String,
)

/** Retained by the conversation owner; publishes only command-palette UI state. */
