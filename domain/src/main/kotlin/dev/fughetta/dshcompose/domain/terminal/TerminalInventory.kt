package dev.fughetta.dshcompose.domain.terminal

import kotlinx.collections.immutable.PersistentList

data class TerminalInventory(
    val environment: TerminalEnvironment,
    val shells: PersistentList<TerminalShell>,
    val rows: PersistentList<TerminalInfo>,
)
