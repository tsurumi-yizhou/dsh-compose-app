package dev.fughetta.dshcompose.di

import dev.fughetta.dshcompose.*
import dev.fughetta.dshcompose.appearance.*
import dev.fughetta.dshcompose.domain.messages.*
import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.messages.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal fun AppViewModel.createTerminal() = run {
    this@createTerminal.ownedModel { dev.fughetta.dshcompose.terminal.TerminalViewModel() }
}

internal fun AppViewModel.createRemoteHostsRepository() = run {
    (this@createRemoteHostsRepository.app as ClientApplication).hostsRepository
}
