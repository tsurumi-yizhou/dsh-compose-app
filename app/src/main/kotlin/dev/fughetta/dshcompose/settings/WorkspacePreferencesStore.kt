package dev.fughetta.dshcompose.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.fughetta.dshcompose.ui.WorkspacePreferences
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val Context.workspacePreferencesData by preferencesDataStore(name = "workspace_preferences")

/** Host identifiers are stable application IDs; never pass a URL or credential as the host key. */
class WorkspacePreferencesStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.workspacePreferencesData)

    private val writes = Mutex()

    fun watch(host: String): Flow<WorkspacePreferences> =
        store.data.map { decode(it[stringPreferencesKey(host)]) }.flowOn(Dispatchers.Default)

    suspend fun update(host: String, transform: (WorkspacePreferences) -> WorkspacePreferences) {
        writes.withLock {
            withContext(Dispatchers.Default) {
                val key = stringPreferencesKey(host)
                store.edit { it[key] = encode(transform(decode(it[key]))) }
            }
        }
    }

    private fun decode(raw: String?): WorkspacePreferences {
        if (raw == null) return WorkspacePreferences()
        val value = Json.parseToJsonElement(raw).jsonObject
        fun strings(key: String) =
            (value[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        return WorkspacePreferences(
            (value["selected"] as? JsonPrimitive)?.contentOrNull,
            strings("pinned").toPersistentSet(),
            (value["groups"] as? JsonObject)
                .orEmpty()
                .mapValues { it.value.jsonPrimitive.content }
                .toPersistentMap(),
            strings("recent").take(4).toPersistentList(),
        )
    }

    private fun encode(value: WorkspacePreferences) = buildJsonObject {
        value.selected?.let { put("selected", it) }
        put("pinned", JsonArray(value.pinned.map(::JsonPrimitive)))
        putJsonObject("groups") { value.groups.forEach { (id, name) -> put(id, name) } }
        put("recent", JsonArray(value.recent.map(::JsonPrimitive)))
    }
        .toString()
}
