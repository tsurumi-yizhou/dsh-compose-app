package dev.fughetta.dshcompose.protocol

import dev.fughetta.dshcompose.domain.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.io.Closeable
import java.math.BigInteger
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

fun parsePluginDisplayMeta(value: JsonElement?): PluginDisplayMeta? =
    (value as? JsonObject)?.let {
        PluginDisplayMeta(
            parseLocalizedText(it["title"]),
            parseLocalizedText(it["description"]),
            it.string("icon"),
            it.string("error"),
        )
    }

private fun JsonObject.strings(key: String) =
    (get(key) as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

private fun pluginFailure(raw: JsonElement?): PluginManagerFailure? =
    (raw as? JsonObject)?.let {
        PluginManagerFailure(it.string("code") ?: "unknown", it.string("message").orEmpty())
    }

fun parsePluginBundle(raw: JsonObject): PluginBundle =
    PluginBundle(
        raw.requiredString("name"),
        raw.boolean("enabled") == true,
        raw.boolean("installed") == true,
        raw.string("description"),
        raw.boolean("official") == true,
        raw.boolean("optional") == true,
        raw.boolean("removable") == true,
        (raw["rows"] as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.let { row ->
                val id = row.string("rowId") ?: return@let null
                val module = row.string("moduleName") ?: return@let null
                PluginBundleRow(
                    id,
                    module,
                    row.string("entryId"),
                    parsePluginDisplayMeta(row["meta"]),
                )
            }
        },
        raw.string("version"),
        raw.string("availability") ?: "profile",
        (raw["installTarget"] as? JsonObject)?.string("spec"),
        raw.string("source"),
        raw.string("readOnlyReason"),
        parsePluginDisplayMeta(raw["meta"]),
        raw.strings("overrides"),
        pluginFailure(raw["error"]),
    )

fun parsePluginOperation(value: JsonElement): PluginOperationResult {
    val raw = value.objectValue()
    val application =
        PluginApplication.entries.firstOrNull { it.wire == raw.string("application") }
            ?: throw HostProtocolException("Invalid plugin application")
    val run = raw["packageResult"] as? JsonObject
    return PluginOperationResult(
        application,
        raw.boolean("changed") == true,
        raw.boolean("enabled"),
        raw.string("restartReason"),
        raw.string("stage"),
        raw.string("target").orEmpty(),
        pluginFailure(raw["error"]),
        raw.strings("warnings"),
        run?.let {
            PluginPackageRun(
                it.long("exitCode"),
                it.string("output").orEmpty(),
                it.boolean("truncated") == true,
                it.string("logPath"),
                it.string("kind"),
                it.boolean("timedOut") == true,
            )
        },
        raw.string("bundle"),
        raw.string("version"),
        raw.strings("pendingBuilds"),
        raw.strings("approvedBuilds"),
        (raw["registries"] as? JsonArray).orEmpty().map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
        },
        raw.string("failedAt"),
    )
}

suspend fun ProtocolClient.pluginRegistries(): PluginRegistries =
    connection.call("pluginManager/registries").objectValue().let {
        PluginRegistries(
            it.string("registry"),
            it.strings("fallbackRegistries"),
            it.string("resolved"),
        )
    }

private val semver =
    Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$",
    )

fun comparePluginVersions(a: String, b: String): Int {
    val left = semver.matchEntire(a)
    val right = semver.matchEntire(b)
    if (left == null || right == null) return a.compareTo(b)
    for (index in 1..3) {
        val result =
            BigInteger(left.groupValues[index]).compareTo(BigInteger(right.groupValues[index]))
        if (result != 0) return result
    }
    val l = left.groupValues[4]
    val r = right.groupValues[4]
    if (l.isEmpty() || r.isEmpty()) return if (l == r) 0 else if (l.isEmpty()) 1 else -1
    val ls = l.split('.')
    val rs = r.split('.')
    for (index in 0 until minOf(ls.size, rs.size)) {
        val ln = ls[index].toBigIntegerOrNull()
        val rn = rs[index].toBigIntegerOrNull()
        val result =
            if (ln != null && rn != null) ln.compareTo(rn)
            else if (ln != null) -1 else if (rn != null) 1 else ls[index].compareTo(rs[index])
        if (result != 0) return result
    }
    return ls.size.compareTo(rs.size)
}

fun parsePluginMarket(value: JsonElement, url: String): PluginMarketIndex {
    val root = value.objectValue()
    if (root.long("format") != 1L) throw HostProtocolException("Invalid plugin market format")
    val metadata = root["metadata"] as? JsonObject ?: JsonObject(emptyMap())
    fun declarations(key: String) =
        (metadata[key] as? JsonObject)
            .orEmpty()
            .mapNotNull { (id, body) ->
                parseLocalizedText((body as? JsonObject)?.get("title"))?.let { id to it }
            }
            .toMap()
    val categories = declarations("categories")
    val labels = declarations("labels")
    val sorts =
        (metadata["sortKeys"] as? JsonObject)
            .orEmpty()
            .mapNotNull { (id, body) ->
                (body as? JsonObject)?.let { raw ->
                    parseLocalizedText(raw["title"])?.let {
                        id to (it to (raw.string("order") != "asc"))
                    }
                }
            }
            .toMap()
    var skipped = 0
    val rows =
        (root["plugins"] as? JsonArray ?: throw HostProtocolException("Missing market plugins"))
            .mapNotNull { item ->
                val raw = item as? JsonObject ?: return@mapNotNull null.also { skipped++ }
                val name =
                    raw.string("name")?.takeIf {
                        Regex("^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$").matches(it)
                    } ?: return@mapNotNull null.also { skipped++ }
                val versions =
                    (raw["versions"] as? JsonArray)
                        .orEmpty()
                        .mapNotNull version@{ body ->
                            val entry = body as? JsonObject ?: return@version null
                            val version =
                                entry.string("version")?.takeIf { semver.matches(it) }
                                    ?: return@version null
                            val source = entry["source"] as? JsonObject ?: return@version null
                            val target =
                                when (source.string("type")) {
                                    "npm" -> {
                                        val registry = source.string("registry")
                                        if (
                                            registry != null &&
                                                runCatching {
                                                        Url(registry).let {
                                                            it.protocol in
                                                                setOf(
                                                                    URLProtocol.HTTP,
                                                                    URLProtocol.HTTPS,
                                                                ) &&
                                                                it.host.isNotBlank() &&
                                                                it.user.isNullOrEmpty() &&
                                                                it.password.isNullOrEmpty() &&
                                                                it.parameters.names().isEmpty() &&
                                                                it.fragment.isEmpty()
                                                        }
                                                    }
                                                    .getOrDefault(false)
                                                    .not()
                                        )
                                            return@version null
                                        "$name@$version"
                                    }
                                    "git" -> {
                                        val address = source.string("url") ?: return@version null
                                        val uri =
                                            runCatching { Url(address) }.getOrNull()
                                                ?: return@version null
                                        val commit =
                                            source.string("commit")?.takeIf {
                                                Regex("[0-9a-f]{40}").matches(it)
                                            } ?: return@version null
                                        if (
                                            uri.protocol != URLProtocol.HTTPS ||
                                                !uri.user.isNullOrEmpty() ||
                                                !uri.password.isNullOrEmpty() ||
                                                uri.parameters.names().isNotEmpty() ||
                                                uri.fragment.isNotEmpty()
                                        )
                                            return@version null
                                        val directory = source.string("path")
                                        if (
                                            directory != null &&
                                                directory.split('/').any {
                                                    it.isBlank() ||
                                                        it == "." ||
                                                        it == ".." ||
                                                        !Regex("[A-Za-z0-9._@+-]+").matches(it)
                                                }
                                        )
                                            return@version null
                                        "git+${if (address.endsWith(".git")) address else "$address.git"}#$commit${directory?.let { "&path:/$it" }.orEmpty()}"
                                    }
                                    else -> return@version null
                                }
                            PluginMarketVersion(
                                version,
                                entry.long("publishTimestamp") ?: 0,
                                target,
                                source.string("registry"),
                            )
                        }
                        .distinctBy { it.version }
                        .sortedWith { a, b -> comparePluginVersions(b.version, a.version) }
                        .toPersistentList()
                if (versions.isEmpty()) return@mapNotNull null.also { skipped++ }
                val meta = raw["metadata"] as? JsonObject ?: JsonObject(emptyMap())
                PluginMarketEntry(
                    name,
                    parseLocalizedText(raw["title"]),
                    parseLocalizedText(raw["description"]),
                    raw.string("icon"),
                    raw.string("author"),
                    raw.string("homepage"),
                    meta.string("category")?.takeIf(categories::containsKey),
                    meta.strings("labels").filter(labels::containsKey),
                    meta.strings("keywords"),
                    (meta["sortKeys"] as? JsonObject)
                        .orEmpty()
                        .mapNotNull { (key, item) ->
                            (item as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)?.let {
                                key to it
                            }
                        }
                        .toMap(),
                    versions,
                )
            }
            .distinctBy { it.name }
            .toPersistentList()
    return PluginMarketIndex(
        url,
        parseLocalizedText(root["title"]),
        parseLocalizedText(root["description"]),
        categories,
        labels,
        sorts,
        rows,
        skipped,
    )
}

/** Reads a format-1 plugin market index (dshfind) over HTTPS, no host credentials or redirects. */
class PluginMarketClient : Closeable {
    suspend fun read(address: String): PluginMarketIndex =
        withContext(Dispatchers.Default) { parsePluginMarket(readDocument(address), address) }

    suspend fun readDocument(address: String): JsonElement =
        withContext(Dispatchers.IO) {
            val url = Url(address)
            require(
                url.protocol == URLProtocol.HTTPS &&
                    url.host.isNotEmpty() &&
                    url.user.isNullOrEmpty() &&
                    url.password.isNullOrEmpty(),
            )
            // Use the platform HTTPS stack instead of Ktor CIO. When the app's
            // network-security-config has per-domain rules (loopback cleartext), Android's
            // RootTrustManager rejects CIO's 2-arg checkServerTrusted with a
            // CertificateException, so any external HTTPS would fail. HttpURLConnection uses the
            // hostname-aware platform trust manager.
            val connection =
                (java.net.URI(address).toURL().openConnection() as java.net.HttpURLConnection)
                    .apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        connectTimeout = 20000
                        readTimeout = 90000
                        setRequestProperty("Accept", "application/json")
                    }
            try {
                val status = connection.responseCode
                if (status != 200) throw HostRemoteException("plugin-market/http-$status")
                val max = 16L * 1024 * 1024
                val sink = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.use { input ->
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > max) throw HostRemoteException("plugin-market/too-large")
                        sink.write(buffer, 0, read)
                    }
                }
                wireJson.parseToJsonElement(sink.toString("UTF-8"))
            } finally {
                connection.disconnect()
            }
        }

    override fun close() = Unit
}
