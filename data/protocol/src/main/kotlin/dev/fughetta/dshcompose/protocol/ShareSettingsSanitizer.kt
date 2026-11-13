package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.settings.*
import java.net.URI
import kotlinx.serialization.json.*

data class SanitizedSharedSettings(val namespace: String, val values: JsonObject, val dropped: Int)

object ShareSettingsSanitizer {
    private val deniedNamespace =
        Regex(
            "account|credential|telemetry|analytics|anonymous|remote-access|remotes|webserver|gateway|otel|login|auth",
            RegexOption.IGNORE_CASE,
        )
    private val secretWords =
        setOf(
            "token",
            "secret",
            "password",
            "passwd",
            "passphrase",
            "cookie",
            "authorization",
            "apikey",
        )
    private val qualifiers =
        setOf("api", "private", "secret", "access", "client", "session", "signing", "license")
    private val absolute = Regex("^(?:/|~/|~$|[A-Za-z]:[\\\\/]|\\\\\\\\|file:)")
    private val credential =
        Regex(
            "(?:sk-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{20,}|(?:ghp_|github_pat_|glpat-|npm_|hf_|xox[baprs]-|sk_live_|rk_test_|whsec_)[A-Za-z0-9_-]{12,}|(?:AKIA|ASIA|LTAI|AKID)[A-Za-z0-9]{12,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|-----BEGIN [A-Z ]*PRIVATE KEY-----)",
        )

    fun shareableNamespace(namespace: String) = !deniedNamespace.containsMatchIn(namespace)

    fun secretName(name: String): Boolean {
        val words =
            name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(Regex("[\\s_\\-.]+"))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
        val last = words.lastOrNull() ?: return false
        return last in secretWords ||
            last == "key" && (words.size == 1 || words[words.size - 2] in qualifiers)
    }

    fun containsSecret(text: String) = credential.containsMatchIn(text)

    fun privateString(text: String): Boolean {
        val value = text.trim()
        if (
            absolute.containsMatchIn(value) ||
                containsSecret(value) ||
                value.startsWith("Bearer ", true)
        )
            return true
        if (
            value.matches(Regex("[A-Za-z0-9+/_=.-]{40,}")) &&
                value.any(Char::isDigit) &&
                value.any(Char::isLetter) &&
                '.' !in value
        )
            return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.host.isNullOrBlank()) return false
        if (uri.userInfo != null) return true
        return uri.rawQuery?.let {
            io.ktor.http.parseQueryString(it).names().any { key ->
                key.lowercase() in
                    setOf(
                        "token",
                        "key",
                        "secret",
                        "sig",
                        "signature",
                        "password",
                        "access_token",
                        "auth",
                        "api_key",
                        "apikey",
                    )
            }
        } == true
    }

    fun namespace(view: SettingsNamespaceView): SanitizedSharedSettings? {
        if (!shareableNamespace(view.ns)) return null
        val secretPaths = view.secrets.map { it.path }.toSet()
        var dropped = 0
        fun clean(
            value: JsonElement,
            schema: SchemaNode?,
            path: List<String>,
            key: String?,
        ): JsonElement? {
            val node = schema?.effective
            if (
                node?.secret == true ||
                    path in secretPaths ||
                    key != null && node?.credentialRef != true && secretName(key)
            )
                return null
            return when (value) {
                is JsonPrimitive ->
                    if (
                        value.isString &&
                            (if (node?.credentialRef == true)
                                absolute.containsMatchIn(value.content.trim())
                            else privateString(value.content))
                    )
                        null
                    else value
                is JsonObject -> {
                    val out = linkedMapOf<String, JsonElement>()
                    var removed = false
                    value.forEach { (name, item) ->
                        val result =
                            clean(
                                item,
                                node?.fields()?.get(name)
                                    ?: if (node?.type == "dict") node.inner else null,
                                path + name,
                                name,
                            )
                        if (result == null) {
                            dropped++
                            removed = true
                        } else out[name] = result
                    }
                    if (removed && out.isEmpty()) null else JsonObject(out)
                }
                is JsonArray -> {
                    val out = value.mapIndexedNotNull { index, item ->
                        clean(item, node?.inner, path + index.toString(), null).also {
                            if (it == null) dropped++
                        }
                    }
                    if (value.isNotEmpty() && out.isEmpty()) null else JsonArray(out)
                }
            }
        }
        val values =
            view.user
                .orEmpty()
                .mapNotNull { (key, value) ->
                    clean(value, view.schema.fields()?.get(key), listOf(key), key)?.let {
                        key to it
                    }
                        ?: run {
                            dropped++
                            null
                        }
                }
                .toMap()
        return SanitizedSharedSettings(view.ns, JsonObject(values), dropped)
    }
}
