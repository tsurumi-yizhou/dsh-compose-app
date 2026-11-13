package dev.fughetta.dshcompose.ui.uiconfig

// What a valid ui.json says, item by item, and where each setting came from (for the settings
// page's 「来自 ui.json」 and 「恢复默认」). Enumerated values stay the schema's own words.

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** What a button, chip, card or shortcut does. [json] is its canonical form. */
sealed interface UiAction {
    val json: JsonObject

    /** Puts [text] into the composer; the person still taps send. */
    data class Send(val text: String) : UiAction {
        override val json
            get() = buildJsonObject {
                put("type", "send")
                put("text", text)
            }
    }

    /** Opens a native page by its schema id. */
    data class Open(val page: String) : UiAction {
        override val json
            get() = buildJsonObject {
                put("type", "open")
                put("page", page)
            }
    }

    /** Runs `/name args` in the current conversation. */
    data class Command(val name: String, val args: String? = null) : UiAction {
        val line
            get() = if (args == null) "/$name" else "/$name $args"

        override val json
            get() = buildJsonObject {
                put("type", "command")
                put("name", name)
                args?.let { put("args", it) }
            }
    }

    /** Opens an https link after a confirmation that shows its address. */
    data class Link(val url: String) : UiAction {
        override val json
            get() = buildJsonObject {
                put("type", "link")
                put("url", url)
            }
    }
}

sealed interface UiHeaderItem {
    val json: JsonElement

    data class Builtin(val id: String) : UiHeaderItem {
        override val json
            get() = JsonPrimitive(id)
    }

    data class Custom(val id: String, val icon: String, val label: String, val action: UiAction) :
        UiHeaderItem {
        override val json
            get() = buildJsonObject {
                put("id", id)
                put("icon", icon)
                put("label", label)
                put("action", action.json)
            }
    }
}

data class UiQuickAction(val label: String, val action: UiAction) {
    val json
        get() = buildJsonObject {
            put("label", label)
            put("action", action.json)
        }
}

data class UiSuggestion(val title: String, val subtitle: String?, val action: UiAction) {
    val json
        get() = buildJsonObject {
            put("title", title)
            subtitle?.let { put("subtitle", it) }
            put("action", action.json)
        }
}

/** A hardware-keyboard shortcut; [keys] is canonical (`ctrl+alt+shift+k`). */
data class UiShortcut(val keys: String, val action: UiAction) {
    val json
        get() = buildJsonObject {
            put("keys", keys)
            put("action", action.json)
        }
}

/** A preset id, or an RGB colour. */
data class UiAccent(val preset: String? = null, val rgb: Int? = null) {
    val json: JsonPrimitive
        get() = JsonPrimitive(preset ?: "#%06X".format(rgb!!))
}

/** Setting ids: what 「恢复默认」 records and the settings page lists. */
object UiSettingKey {
    const val HEADER_ACTIONS = "header.actions"
    const val QUICK_ACTIONS = "composer.quickActions"
    const val SUGGESTIONS = "emptyState.suggestions"
    const val DRAWER_GROUPS = "drawer.groups"
    const val DRAWER_SORT = "drawer.sort"
    const val DRAWER_FILTER = "drawer.filter"
    const val DENSITY = "density"
    const val THEME_MODE = "theme.mode"

    /** `theme.accentPreset` or `theme.accent`, whichever the file uses. */
    const val ACCENT = "theme.accent"
    const val SHORTCUTS = "shortcuts"

    fun hide(item: String) = "hide.$item"

    fun seat(slot: String, pkg: String) = "plugins.seats|$slot|$pkg"
}

/** Where a setting sits in the file ([pointer]) and what it says ([value], canonical JSON). */
data class UiConfigEntry(val key: String, val pointer: String, val value: JsonElement) {
    /** Short fingerprint of [value]: a 「恢复默认」 lasts while it matches. */
    val hash: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(canonical(value).toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    /** The value for people: a string as is, anything else as compact JSON, cut to 200. */
    val display: String
        get() {
            val text =
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
            val points = text.codePointCount(0, text.length)
            return if (points <= 200) text
            else text.substring(0, text.offsetByCodePoints(0, 200)) + "…"
        }
}

private fun canonical(value: JsonElement): String =
    when (value) {
        is JsonObject ->
            value.keys.sorted().joinToString(",", "{", "}") {
                JsonPrimitive(it).toString() + ":" + canonical(value.getValue(it))
            }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

data class UiConfig(
    /** In display order. */
    val headerActions: List<UiHeaderItem>? = null,
    val quickActions: List<UiQuickAction>? = null,
    val suggestions: List<UiSuggestion>? = null,
    val drawerGroups: List<String>? = null,
    val drawerSort: String? = null,
    val drawerFilter: String? = null,
    val density: String? = null,
    val hide: List<String> = emptyList(),
    val themeMode: String? = null,
    val accent: UiAccent? = null,
    val shortcuts: List<UiShortcut>? = null,
    /** slot → package → seat mode. */
    val seats: Map<String, Map<String, String>> = emptyMap(),
    val entries: Map<String, UiConfigEntry> = emptyMap(),
) {
    fun hides(item: String) = item in hide

    /** This configuration with [keys] set aside (「恢复默认」). */
    fun without(keys: Set<String>): UiConfig {
        if (keys.none(entries::containsKey)) return this
        fun keep(key: String) = key !in keys
        return UiConfig(
            headerActions = headerActions.takeIf { keep(UiSettingKey.HEADER_ACTIONS) },
            quickActions = quickActions.takeIf { keep(UiSettingKey.QUICK_ACTIONS) },
            suggestions = suggestions.takeIf { keep(UiSettingKey.SUGGESTIONS) },
            drawerGroups = drawerGroups.takeIf { keep(UiSettingKey.DRAWER_GROUPS) },
            drawerSort = drawerSort.takeIf { keep(UiSettingKey.DRAWER_SORT) },
            drawerFilter = drawerFilter.takeIf { keep(UiSettingKey.DRAWER_FILTER) },
            density = density.takeIf { keep(UiSettingKey.DENSITY) },
            hide = hide.filter { keep(UiSettingKey.hide(it)) },
            themeMode = themeMode.takeIf { keep(UiSettingKey.THEME_MODE) },
            accent = accent.takeIf { keep(UiSettingKey.ACCENT) },
            shortcuts = shortcuts.takeIf { keep(UiSettingKey.SHORTCUTS) },
            seats =
                seats
                    .mapValues { (slot, packages) ->
                        packages.filterKeys { keep(UiSettingKey.seat(slot, it)) }
                    }
                    .filterValues { it.isNotEmpty() },
            entries = entries.filterKeys(::keep),
        )
    }

    companion object {
        val Empty = UiConfig()
    }
}

enum class UiIssueKind {
    /** The whole file does not apply. */
    Fatal,

    /** That item is left out. */
    Invalid,

    /** Read but not used (unknown key, duplicate, over the limit). */
    Ignored,

    /** Used after a fix (cut to length, `/` dropped). */
    Adjusted;

    val dropsSomething
        get() = this != Adjusted

    val wire
        get() = name.lowercase()
}

data class UiConfigIssue(val pointer: String, val message: UiMessage, val kind: UiIssueKind)

data class UiConfigLoad(
    val config: UiConfig,
    val issues: List<UiConfigIssue> = emptyList(),
    /** The previous good configuration still applies (this read was fatal). */
    val fromLastGood: Boolean = false,
) {
    val fatal
        get() = issues.any { it.kind == UiIssueKind.Fatal }

    val notAppliedCount
        get() = issues.count { it.kind.dropsSomething }

    val appliedCount
        get() = if (fatal) 0 else config.entries.size
}
