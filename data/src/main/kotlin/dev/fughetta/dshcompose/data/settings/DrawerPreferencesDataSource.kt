package dev.fughetta.dshcompose.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.fughetta.dshcompose.domain.sessions.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal val Context.drawerPreferencesData by preferencesDataStore(name = "drawer_preferences")

/** Uses the same stable host IDs as WorkspacePreferencesStore, never URLs or credentials. */
internal class DrawerPreferencesDataSource(private val store: DataStore<Preferences>) {

    fun watch(host: String): Flow<DrawerPreferences> =
        store.data.map { decode(it[stringPreferencesKey(host)]) }.flowOn(Dispatchers.Default)

    suspend fun update(host: String, transform: (DrawerPreferences) -> DrawerPreferences) =
        withContext(Dispatchers.Default) {
            val key = stringPreferencesKey(host)
            store.edit { it[key] = encode(transform(decode(it[key]))) }
            Unit
        }

    private fun decode(raw: String?): DrawerPreferences {
        if (raw == null) return DrawerPreferences()
        val obj = Json.parseToJsonElement(raw).jsonObject
        val assigned = hashSetOf<String>()
        val folderIds = hashSetOf<String>()
        val folders =
            (obj["folders"] as? JsonArray)
                .orEmpty()
                .mapNotNull { value ->
                    val f = value as? JsonObject ?: return@mapNotNull null
                    val id =
                        f["id"]?.jsonPrimitive?.contentOrNull?.takeIf {
                            it.isNotBlank() && folderIds.add(it)
                        } ?: return@mapNotNull null
                    val name =
                        f["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                            ?: return@mapNotNull null
                    DrawerFolder(
                        id,
                        cleanFolderName(name),
                        f["tint"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..7 },
                        f["emoji"]?.jsonPrimitive?.contentOrNull,
                        f["collapsed"]?.jsonPrimitive?.booleanOrNull == true,
                        (f["sessions"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .filter(assigned::add)
                            .toPersistentList(),
                    )
                }
                .toPersistentList()
        val createdAt =
            (obj["createdAt"] as? JsonObject)
                .orEmpty()
                .mapNotNull { (id, at) -> (at as? JsonPrimitive)?.longOrNull?.let { id to it } }
                .toMap()
                .toPersistentMap()
        val seen =
            (obj["seen"] as? JsonObject)
                .orEmpty()
                .mapNotNull { (id, at) -> (at as? JsonPrimitive)?.longOrNull?.let { id to it } }
                .toMap()
                .toPersistentMap()
        return DrawerPreferences(
            DrawerView(
                folders,
                if (obj["sort"]?.jsonPrimitive?.contentOrNull == "Created") DrawerSort.Created
                else DrawerSort.Activity,
                createdAt,
            ),
            DrawerSeen(obj["initialActivity"]?.jsonPrimitive?.longOrNull, seen),
        )
    }

    private fun encode(value: DrawerPreferences): String = buildJsonObject {
        put("sort", value.view.sort.name)
        putJsonObject("createdAt") { value.view.createdAt.forEach { (id, at) -> put(id, at) } }
        putJsonArray("folders") {
            value.view.folders.forEach { f ->
                add(
                    buildJsonObject {
                        put("id", f.id)
                        put("name", f.name)
                        f.tint?.let { put("tint", it) }
                        f.emoji?.let { put("emoji", it) }
                        put("collapsed", f.collapsed)
                        put("sessions", JsonArray(f.sessionIds.map(::JsonPrimitive)))
                    },
                )
            }
        }
        value.seen.initialActivity?.let { put("initialActivity", it) }
        putJsonObject("seen") { value.seen.seen.forEach { (id, at) -> put(id, at) } }
    }
        .toString()
}
