package dev.fughetta.dshcompose.data.terminal

import dev.fughetta.dshcompose.domain.terminal.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*

suspend fun readTerminalInventory(owner: TerminalRepository, session: String): TerminalInventory =
    coroutineScope {
        val environment = async { owner.environment(session) }
        val shells = async { owner.shells(session) }
        val rows = async { owner.list(session) }
        TerminalInventory(environment.await(), shells.await(), rows.await())
    }
