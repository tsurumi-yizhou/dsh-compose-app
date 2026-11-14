package dev.fughetta.dshcompose.ui.commands

import dev.fughetta.dshcompose.domain.commands.SlashCommand
import kotlinx.collections.immutable.*

data class CommandsUiState(
    val sessionId: String,
    val commands: PersistentList<SlashCommand> = persistentListOf(),
    val hint: SlashCommand? = null,
    val loading: Boolean = false,
    val error: String? = null,
)
