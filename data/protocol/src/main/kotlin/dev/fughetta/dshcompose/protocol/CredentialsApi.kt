package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.providers.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Only metadata is read: the credentials API never returns the stored key. */
suspend fun ProtocolClient.describeCredentials(
    refs: Collection<String>,
): PersistentMap<String, CredentialStatus> =
    withContext(Dispatchers.Default) {
        refs.forEach(::checkCredentialRef)
        val value =
            connection
                .call(
                    "credentials/describe",
                    buildJsonObject {
                        put("refs", JsonArray(refs.distinct().map(::JsonPrimitive)))
                    },
                )
                .objectValue()
        refs
            .mapNotNull { ref ->
                (value[ref] as? JsonObject)?.let { row ->
                    ref to
                        CredentialStatus(
                            row.boolean("configured") == true,
                            row.boolean("writable") == true,
                            row.string("source"),
                        )
                }
            }
            .toMap()
            .toPersistentMap()
    }

suspend fun ProtocolClient.setProviderKey(ref: String, key: ProviderKey) {
    checkCredentialRef(ref)
    connection.call(
        "credentials/set",
        buildJsonObject {
            put("ref", ref)
            put("value", key.text())
        },
    )
}

suspend fun ProtocolClient.clearProviderKey(ref: String) {
    checkCredentialRef(ref)
    connection.call("credentials/unset", buildJsonObject { put("ref", ref) })
}

/** Profiles contain credential references; environment values and headers are never retained. */
suspend fun ProtocolClient.providerAccounts(): PersistentList<ProviderAccount> =
    withContext(Dispatchers.Default) { providerSettings().accounts() }

internal fun checkCredentialRef(ref: String) {
    require(validCredentialRef(ref)) { "Invalid credential reference" }
}

internal fun ProviderKey.text(): String = useCharacters { String(it) }
