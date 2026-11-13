package dev.fughetta.dshcompose.domain.nativeplugins

import kotlinx.coroutines.flow.StateFlow

class NativePluginFeed(val state: StateFlow<NativePluginData>, private val dispose: () -> Unit) :
    AutoCloseable {
    override fun close() = dispose()
}
