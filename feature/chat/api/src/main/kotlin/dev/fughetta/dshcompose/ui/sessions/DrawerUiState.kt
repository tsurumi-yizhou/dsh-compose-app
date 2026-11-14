package dev.fughetta.dshcompose.ui.sessions

import dev.fughetta.dshcompose.domain.sessions.*
import dev.fughetta.dshcompose.ui.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DrawerUiState(
    val loaded: Boolean = false,
    val view: DrawerView = DrawerView(),
    val seen: DrawerSeen = DrawerSeen(),
    val filter: DrawerFilter = DrawerFilter.All,
    val selection: DrawerSelection = DrawerSelection(),
    val failure: String? = null,
    val failureRevision: Long = 0,
)
