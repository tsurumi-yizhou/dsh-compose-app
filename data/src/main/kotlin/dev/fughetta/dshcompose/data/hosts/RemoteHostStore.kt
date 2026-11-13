package dev.fughetta.dshcompose.data.hosts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.fughetta.dshcompose.data.link.LinkFailure
import dev.fughetta.dshcompose.domain.hosts.RemoteHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

private val Context.remoteHostsData by preferencesDataStore(name = "compose_hosts")

fun createRemoteHostStore(context: Context): RemoteHostStore =
    RemoteHostStore(context.applicationContext.remoteHostsData)

/** Metadata and encrypted grants commit together, so a saved service is reconnectable. */
class RemoteHostStore
internal constructor(
    private val store: DataStore<Preferences>,
    private val cipher: DeviceCredentialCipher = DeviceCredentialCipher(),
    private val retireCredentials: () -> Unit = cipher::clearRetiredKey,
) {
    private val key = stringPreferencesKey("services")

    private fun rows(text: String?) = Json.parseToJsonElement(text ?: "[]").jsonArray

    private fun host(row: JsonObject) =
        RemoteHost(
            row.getValue("id").jsonPrimitive.content,
            row.getValue("name").jsonPrimitive.content,
            row.getValue("url").jsonPrimitive.content,
        )

    suspend fun load(): List<RemoteHost> =
        withContext(Dispatchers.IO) {
            retireCredentials()
            rows(store.data.first()[key]).map { host(it.jsonObject) }
        }

    suspend fun credential(id: String): String? =
        withContext(Dispatchers.IO) {
            val row =
                rows(store.data.first()[key])
                    .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == id }
                    ?.jsonObject ?: return@withContext null
            val encrypted = row["credential"]?.jsonPrimitive?.content ?: return@withContext null
            try {
                cipher.decrypt(host(row), encrypted)
            } catch (_: java.security.GeneralSecurityException) {
                throw LinkFailure("device/credential-unavailable")
            } catch (_: IllegalArgumentException) {
                throw LinkFailure("device/credential-unavailable")
            }
        }

    suspend fun save(
        hosts: List<RemoteHost>,
        credentialHost: String? = null,
        credential: String? = null,
    ) =
        withContext(Dispatchers.IO) {
            val replacement =
                if (credentialHost != null && credential != null)
                    cipher.encrypt(hosts.single { it.id == credentialHost }, credential)
                else null
            store.edit { preferences ->
                val previous =
                    rows(preferences[key]).associateBy {
                        it.jsonObject.getValue("id").jsonPrimitive.content
                    }
                preferences[key] =
                    JsonArray(
                            hosts.map { host ->
                                buildJsonObject {
                                    put("id", host.id)
                                    put("name", host.name)
                                    put("url", host.url)
                                    val old = previous[host.id]?.jsonObject
                                    val encrypted =
                                        if (host.id == credentialHost) replacement
                                        else
                                            old?.takeIf {
                                                    it["url"]?.jsonPrimitive?.content == host.url
                                                }
                                                ?.get("credential")
                                                ?.jsonPrimitive
                                                ?.content
                                    encrypted?.let { put("credential", it) }
                                }
                            },
                        )
                        .toString()
            }
        }
}
