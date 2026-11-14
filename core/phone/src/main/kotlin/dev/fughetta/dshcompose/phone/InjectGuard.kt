package dev.fughetta.dshcompose.phone

import android.os.SystemClock

/**
 * When this service last injected input; the overlay's guarded buttons ignore touches inside it.
 */
object InjectGuard {
    @Volatile
    var until = 0L
        private set

    fun mark(durationMs: Long) {
        until = maxOf(until, SystemClock.uptimeMillis() + durationMs + 400)
    }

    fun active() = SystemClock.uptimeMillis() < until
}
