package dev.fughetta.dshcompose.phone

import java.util.concurrent.atomic.AtomicBoolean

/** Shared by both input drivers and tool admission; never affected by permission presets. */
object ApkInstallGate {
    private val held = AtomicBoolean()
    val active: Boolean
        get() = held.get()

    fun acquire(): Boolean = held.compareAndSet(false, true)

    fun release() {
        held.set(false)
    }
}
