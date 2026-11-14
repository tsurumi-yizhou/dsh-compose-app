package dev.fughetta.dshcompose.ui.nativeplugins

import dev.fughetta.dshcompose.domain.features.ModuleStatus
import dev.fughetta.dshcompose.domain.nativeplugins.*
import kotlinx.serialization.json.*

data class NativePluginPanelState(
    val panel: NativePluginPanel,
    val scope: NativePluginScope,
    val view: JsonElement = JsonNull,
    val data: JsonElement = JsonNull,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val micro: String? = null,
    val actionRevision: Long = 0,
    val accepted: Set<String> = emptySet(),
    val rejected: Set<String> = emptySet(),
)

data class NativePluginConsent(
    val pkg: String,
    val title: String,
    val wants: Set<String>,
    val url: String? = null,
)

data class NativeTurnNote(val pkg: String, val text: String)

data class NativePluginsState(
    val plugins: List<NativePlugin> = emptyList(),
    val panel: NativePluginPanelState? = null,
    val strips: Map<String, JsonElement> = emptyMap(),
    val turnNotes: Map<String, List<NativeTurnNote>> = emptyMap(),
    val disabled: Set<String> = emptySet(),
    val busy: Set<String> = emptySet(),
    val consent: NativePluginConsent? = null,
    val error: String? = null,
    val issues: Map<String, List<String>> = emptyMap(),
    val reachable: Boolean = false,
    val modules: Map<String, ModuleStatus> = emptyMap(),
) {
    fun enabled(pkg: String, surface: String) =
        "$pkg|module" !in disabled &&
            (modules[pkg] == null || modules[pkg] == ModuleStatus.Active) &&
            "$pkg|$surface" !in disabled
}

data class NativePluginViewAction(
    val id: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val gesture: Boolean = true,
    val atNanos: Long = System.nanoTime(),
)

data class NativePluginDispatch(
    val hostKey: String,
    val scope: NativePluginScope,
    val effect: NativePluginEffect,
    val binding: Int,
    val muxGeneration: dev.fughetta.dshcompose.domain.hosts.ConnectionGeneration?,
)

sealed interface NativePluginEffect {
    data class Insert(val text: String) : NativePluginEffect

    data class Command(val line: String) : NativePluginEffect

    data class Open(val page: String) : NativePluginEffect

    data class Link(val url: String) : NativePluginEffect

    data class Toast(val text: String) : NativePluginEffect
}

val nativePluginComponents: Set<String> =
    setOf(
        "stack",
        "row",
        "text",
        "metric",
        "chart",
        "compare",
        "steps",
        "callout",
        "options",
        "confirm",
        "list",
        "keyValue",
        "progress",
        "table",
        "actions",
        "image",
        "textField",
        "number",
        "stepper",
        "toggle",
        "slider",
        "select",
        "segmented",
        "chips",
        "date",
        "time",
        "rating",
        "form",
        "tabs",
        "collapsible",
        "accordion",
        "carousel",
        "timeline",
        "grid",
        "badge",
        "tag",
        "avatar",
        "codeBlock",
        "divider",
        "spacer",
        "emptyState",
        "gallery",
    )

/** Bindings are data lookup only; returned data is never walked as another template. */
fun nativePluginResolve(template: JsonElement, data: JsonElement, depth: Int = 0): JsonElement {
    if (depth > 64) return JsonNull
    if (template is JsonObject && template.keys == setOf("$"))
        return nativePluginLookup(data, template.viewString("$") ?: return JsonNull)
    if (template is JsonObject && template.keys == setOf("="))
        return template.viewString("=")?.let { nativePluginFormula(it, data) }?.let(::JsonPrimitive)
            ?: JsonNull
    return when (template) {
        is JsonObject ->
            JsonObject(
                template.mapValues { (key, value) ->
                    if (key == "type" && value !is JsonPrimitive) JsonNull
                    else nativePluginResolve(value, data, depth + 1)
                },
            )
        is JsonArray -> JsonArray(template.map { nativePluginResolve(it, data, depth + 1) })
        else -> template
    }
}

fun nativePluginLookup(data: JsonElement, path: String): JsonElement {
    if (!path.startsWith("data") || path.length > 256) return JsonNull
    var current = data
    var offset = 4
    var count = 0
    val segment = Regex("\\.([^.\\[\\]]+)|\\[([0-9]+)]")
    while (offset < path.length) {
        if (++count > 16) return JsonNull
        val match =
            segment.find(path, offset)?.takeIf { it.range.first == offset } ?: return JsonNull
        val key = match.groupValues[1].ifEmpty { match.groupValues[2] }
        val at = current
        current =
            when (at) {
                is JsonObject -> at[key]
                is JsonArray -> key.toIntOrNull()?.let { at.getOrNull(it) }
                else -> null
            } ?: return JsonNull
        offset = match.range.last + 1
    }
    return current
}

fun nativePluginWritable(path: String): Boolean =
    path.startsWith("data.") &&
        path.length <= 256 &&
        path.removePrefix("data.").split('.').let { parts ->
            parts.size in 1..16 &&
                parts.all {
                    it.matches(Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")) &&
                        it !in setOf("__proto__", "prototype", "constructor")
                }
        }

fun nativePluginWrite(data: JsonElement, path: String, value: JsonElement): JsonElement {
    if (!nativePluginWritable(path)) return data
    fun write(at: JsonElement, keys: List<String>): JsonElement {
        val copy = (at as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        copy[keys.first()] =
            if (keys.size == 1) value else write(copy[keys.first()] ?: JsonNull, keys.drop(1))
        return JsonObject(copy)
    }
    return write(data, path.removePrefix("data.").split('.'))
}

fun JsonObject.viewString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.viewBool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true

fun JsonObject.viewObjects(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
