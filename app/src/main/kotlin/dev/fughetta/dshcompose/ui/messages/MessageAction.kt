package dev.fughetta.dshcompose.ui.messages

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MessageAction
internal constructor(
    val menu: MessageMenuState,
    private val current: () -> Boolean,
    private val finish: () -> Unit,
) {
    fun isCurrent() = current()

    /** Release the menu before navigation binds another conversation. */
    fun complete() = finish()
}
