package dev.fughetta.dshcompose.data.settings

import dev.fughetta.dshcompose.domain.settings.PreferenceGroup
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes read-modify-write operations and waits for the process-owned writer's answer. */
internal class ShellPreferencesDataSource(private val preferences: PreferenceGroup) {
    val data = preferences.data
    private val mutex = Mutex()

    suspend fun edit(change: PreferenceGroup.(PreferenceGroup.Edit) -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val edit = preferences.edit()
                preferences.change(edit)
                if (!edit.commit()) throw java.io.IOException("settings/write-failed")
            }
        }
    }
}
