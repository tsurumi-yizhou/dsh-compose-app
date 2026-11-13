package dev.fughetta.dshcompose.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.fughetta.dshcompose.domain.settings.PreferenceGroup
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

private val Context.localSettings by preferencesDataStore(name = "local_settings")

/** One process owner, including services, receivers and activity resource callbacks. */
fun Context.localPreferences(name: String): PreferenceGroup = LocalPreferences.get(this).group(name)

/**
 * Synchronous Android callbacks read a snapshot. Edits update it immediately and are written in
 * submission order by a process-owned coroutine. Failed writes are rolled back, without losing
 * later pending edits. Callers that require durable confirmation use commit on a worker thread.
 */
internal class PreferenceStorage(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
    initial: Preferences,
    private val reportFailure: () -> Unit,
) {
    private class Write(
        val change: (MutablePreferences) -> Unit,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val gate = Any()
    private var persisted = initial
    private val pending = linkedSetOf<Write>()
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private val snapshot = MutableStateFlow(initial)
    val data: StateFlow<Preferences> = snapshot.asStateFlow()

    init {
        scope.launch {
            for (write in queue) {
                var saved: Preferences? = null
                try {
                    saved = store.edit(write.change)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    reportFailure()
                }
                synchronized(gate) {
                    if (saved != null) persisted = saved
                    pending.remove(write)
                    refresh()
                }
                write.result.complete(saved != null)
            }
        }
    }

    private fun refresh() {
        snapshot.value =
            persisted
                .toMutablePreferences()
                .apply { pending.forEach { it.change(this) } }
                .toPreferences()
    }

    fun submit(change: (MutablePreferences) -> Unit): Deferred<Boolean> =
        synchronized(gate) {
            val write = Write(change)
            check(queue.trySend(write).isSuccess) { "preferences/writer-unavailable" }
            pending.add(write)
            refresh()
            write.result
        }
}

internal class LocalPreferences private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = context.localSettings
    // attachBaseContext needs the saved language before it can create localized resources.
    // Only the first access waits for disk; every group thereafter shares this snapshot.
    private val initial = runBlocking(Dispatchers.IO) { store.data.first() }
    private val storage =
        PreferenceStorage(store, scope, initial) {
            Log.e("LocalPreferences", "preferences/write-failed")
        }
    private val groups = ConcurrentHashMap<String, PreferenceGroup>()

    fun group(name: String): PreferenceGroup = groups.getOrPut(name) { storage.group(name) }

    companion object {
        @Volatile private var instance: LocalPreferences? = null

        fun get(context: Context): LocalPreferences =
            instance
                ?: synchronized(this) {
                    instance
                        ?: LocalPreferences(context.applicationContext ?: context).also {
                            instance = it
                        }
                }
    }
}

/** Binds namespace keys and ordered writes to the shared DataStore owner. */
internal fun PreferenceStorage.group(name: String): PreferenceGroup {
    val prefix = "${name.length}:$name:"
    val updates =
        data
            .map { preferences ->
                preferences
                    .asMap()
                    .filterKeys { it.name.startsWith(prefix) }
                    .mapKeys { it.key.name.removePrefix(prefix) }
            }
            .distinctUntilChanged()
    return PreferenceGroup(
        data = updates,
        read = { key -> data.value.asMap()[stringPreferencesKey(prefix + key)] },
        write = { change, wait ->
            val result = submit { preferences ->
                if (change.clear)
                    preferences
                        .asMap()
                        .keys
                        .filter { it.name.startsWith(prefix) }
                        .forEach { preferences.remove(it) }
                for ((key, value) in change.values) {
                    val full = prefix + key
                    when (value) {
                        null -> preferences.remove(stringPreferencesKey(full))
                        is String -> preferences[stringPreferencesKey(full)] = value
                        is Boolean -> preferences[booleanPreferencesKey(full)] = value
                        is Int -> preferences[intPreferencesKey(full)] = value
                        is Long -> preferences[longPreferencesKey(full)] = value
                        is Float -> preferences[floatPreferencesKey(full)] = value
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            preferences[stringSetPreferencesKey(full)] = value as Set<String>
                        }
                    }
                }
            }
            if (wait) runBlocking { result.await() } else true
        },
    )
}
