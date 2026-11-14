package dev.fughetta.dshcompose.ui.uiconfig

// ui.json v1 → [UiConfigLoad]. Every item stands alone: a wrong one is left out with a JSON
// Pointer and a short reason, the rest applies. A file that cannot be read as a whole (syntax,
// size, version) keeps the last good configuration.
//
// `app/src/main/assets/skills/dsh-compose-ui/check.mjs` is this file's twin for the agent on the
// host: same
// traversal order, same pointers, same words. Change both together; UiConfigParserTest runs the
// shared fixtures through each.
import dev.fughetta.dshcompose.R
import kotlinx.serialization.json.*

private fun text(id: Int, vararg args: Any) = UiMessage.Text(id, args.toList())

/** A whole-file problem found before parsing (too large, not text). */
fun uiConfigBroken(message: UiMessage, lastGood: UiConfig?) =
    UiConfigLoad(
        lastGood ?: UiConfig.Empty,
        listOf(UiConfigIssue("", message, UiIssueKind.Fatal)),
        fromLastGood = lastGood != null,
    )

/** Parses ui.json [text]. On a fatal issue the result carries [lastGood] (or the empty config). */
fun parseUiConfig(text: String, schema: UiSchema, lastGood: UiConfig? = null): UiConfigLoad {
    fun fatal(pointer: String, message: UiMessage) =
        UiConfigLoad(
            lastGood ?: UiConfig.Empty,
            listOf(UiConfigIssue(pointer, message, UiIssueKind.Fatal)),
            fromLastGood = lastGood != null,
        )

    if (text.toByteArray().size > schema.maxBytes)
        return fatal("", text(R.string.ui_config_file_too_large))
    val doc =
        try {
            readUiJson(text, schema.maxDepth)
        } catch (e: UiJsonSyntaxError) {
            return fatal("", e.text)
        }
    @Suppress("UNCHECKED_CAST")
    val root =
        doc.value as? Map<String, Any?>
            ?: return fatal("", text(R.string.ui_config_root_not_object))
    if (!root.containsKey("version"))
        return fatal("/version", text(R.string.ui_config_version_missing))
    val version =
        root["version"] as? Int
            ?: return fatal("/version", text(R.string.ui_config_version_not_int))
    if (version < 1) return fatal("/version", text(R.string.ui_config_version_too_low))
    if (version > schema.version)
        return fatal("/version", text(R.string.ui_config_version_too_high, version))

    val v = UiValidator(schema)
    for (pointer in doc.duplicateKeys) v.issue(
        pointer,
        text(R.string.ui_config_duplicate_key),
        UiIssueKind.Ignored,
    )
    val config = v.root(root)
    return UiConfigLoad(config, v.issues.toList())
}

private val controlChars = Regex("[\u0000-\u001F\u007F-\u009F  ]")
private val sendControl = Regex("[\u0000-\u0008\u000B-\u001F\u007F-\u009F]")
private val lineBreaks = Regex("\r\n|\r|\n|\t")
private val headerId = Regex("^[a-z][a-zA-Z0-9-]{0,31}$")
private val commandName = Regex("^[A-Za-z0-9][A-Za-z0-9:._-]{0,63}$")
private val httpsUrl =
    Regex("^https://([A-Za-z0-9-]+\\.)*[A-Za-z0-9-]+(:[0-9]{1,5})?([/?#][^\\s]*)?$")
private val badPercent = Regex("%(?![0-9A-Fa-f]{2})")
private val hexColor = Regex("^#[0-9A-Fa-f]{6}$")
private val slotName = Regex("^[a-z][A-Za-z0-9-]*(\\.[A-Za-z0-9-]+)*$")
private val packageName = Regex("^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$")
private val functionKey = Regex("^f([1-9]|1[0-2])$")

private fun String.points() = codePointCount(0, length)

private fun String.takePoints(n: Int) = substring(0, offsetByCodePoints(0, n))

@Suppress("UNCHECKED_CAST")
private class UiValidator(private val schema: UiSchema) {
    val issues = mutableListOf<UiConfigIssue>()
    val entries = LinkedHashMap<String, UiConfigEntry>()

    fun issue(pointer: String, message: UiMessage, kind: UiIssueKind = UiIssueKind.Invalid) {
        issues += UiConfigIssue(pointer, message, kind)
    }

    fun entry(key: String, pointer: String, value: JsonElement) {
        entries[key] = UiConfigEntry(key, pointer, value)
    }

    fun child(pointer: String, key: String) = "$pointer/${escapePointerToken(key)}"

    fun unknownKeys(map: Map<String, Any?>, pointer: String, known: List<String>) {
        for (key in map.keys) if (key !in known)
            issue(child(pointer, key), text(R.string.ui_config_unknown_key), UiIssueKind.Ignored)
    }

    fun obj(value: Any?, pointer: String): Map<String, Any?>? {
        if (value is Map<*, *>) return value as Map<String, Any?>
        issue(pointer, text(R.string.ui_config_not_object))
        return null
    }

    fun array(value: Any?, pointer: String): List<Any?>? {
        if (value is List<*>) return value
        issue(pointer, text(R.string.ui_config_not_array))
        return null
    }

    /** One value of [options]; null (with an issue) otherwise. */
    fun choice(
        value: Any?,
        pointer: String,
        options: List<String>,
        noun: Int = R.string.ui_config_value_noun,
    ): String? {
        if (value !is String) {
            issue(pointer, text(R.string.ui_config_not_string))
            return null
        }
        if (value in options) return value
        issue(pointer, UiMessage.BadValue(noun, value, options))
        return null
    }

    /**
     * A one-line label: line breaks become spaces, control characters go, ends are trimmed, more
     * than [max] characters are cut.
     */
    fun label(value: Any?, pointer: String, max: Int): String? {
        if (value !is String) {
            issue(pointer, text(R.string.ui_config_not_string))
            return null
        }
        val clean = value.replace(lineBreaks, " ").replace(controlChars, "").trim()
        if (clean.isEmpty()) {
            issue(pointer, text(R.string.ui_config_empty))
            return null
        }
        if (clean.points() <= max) return clean
        issue(pointer, UiMessage.Count(R.plurals.ui_config_truncated, max), UiIssueKind.Adjusted)
        return clean.takePoints(max).trim()
    }

    fun root(root: Map<String, Any?>): UiConfig {
        var config = UiConfig()
        for ((key, value) in root) {
            val p = child("", key)
            when (key) {
                "version",
                "\$schema" -> Unit
                "header" -> config = config.copy(headerActions = header(value, p))
                "composer" ->
                    obj(value, p)?.let { map ->
                        unknownKeys(map, p, listOf("quickActions"))
                        if (map.containsKey("quickActions"))
                            config =
                                config.copy(
                                    quickActions =
                                        quickActionList(
                                            map["quickActions"],
                                            child(p, "quickActions"),
                                        ),
                                )
                    }
                "emptyState" ->
                    obj(value, p)?.let { map ->
                        unknownKeys(map, p, listOf("suggestions"))
                        if (map.containsKey("suggestions"))
                            config =
                                config.copy(
                                    suggestions =
                                        suggestionList(map["suggestions"], child(p, "suggestions")),
                                )
                    }
                "drawer" ->
                    obj(value, p)?.forEach { (k, field) ->
                        val fp = child(p, k)
                        when (k) {
                            "groups" -> config = config.copy(drawerGroups = groupList(field, fp))
                            "sort" ->
                                choice(field, fp, schema.drawerSorts)?.let {
                                    config = config.copy(drawerSort = it)
                                    entry(UiSettingKey.DRAWER_SORT, fp, JsonPrimitive(it))
                                }
                            "filter" ->
                                choice(field, fp, schema.drawerFilters)?.let {
                                    config = config.copy(drawerFilter = it)
                                    entry(UiSettingKey.DRAWER_FILTER, fp, JsonPrimitive(it))
                                }
                            else ->
                                issue(fp, text(R.string.ui_config_unknown_key), UiIssueKind.Ignored)
                        }
                    }
                "density" ->
                    choice(value, p, schema.densities)?.let {
                        config = config.copy(density = it)
                        entry(UiSettingKey.DENSITY, p, JsonPrimitive(it))
                    }
                "hide" -> config = config.copy(hide = hideList(value, p))
                "theme" ->
                    obj(value, p)?.forEach { (k, field) ->
                        config = theme(config, k, field, child(p, k))
                    }
                "shortcuts" -> config = config.copy(shortcuts = shortcutList(value, p))
                "plugins" ->
                    obj(value, p)?.let { map ->
                        unknownKeys(map, p, listOf("seats"))
                        if (map.containsKey("seats"))
                            config = config.copy(seats = seatMap(map["seats"], child(p, "seats")))
                    }
                else -> issue(p, text(R.string.ui_config_unknown_key), UiIssueKind.Ignored)
            }
        }
        return config.copy(entries = entries.toMap())
    }

    fun theme(config: UiConfig, key: String, field: Any?, fp: String): UiConfig {
        val accent = config.accent
        when (key) {
            "mode" ->
                choice(field, fp, schema.themeModes)?.let {
                    entry(UiSettingKey.THEME_MODE, fp, JsonPrimitive(it))
                    return config.copy(themeMode = it)
                }
            "accentPreset" ->
                choice(field, fp, schema.accentPresets)?.let { id ->
                    if (accent?.rgb != null)
                        issue(
                            entries.getValue(UiSettingKey.ACCENT).pointer,
                            text(R.string.ui_config_accent_both),
                            UiIssueKind.Ignored,
                        )
                    entry(UiSettingKey.ACCENT, fp, JsonPrimitive(id))
                    return config.copy(accent = UiAccent(preset = id))
                }
            "accent" ->
                when {
                    field !is String -> issue(fp, text(R.string.ui_config_not_string))
                    !hexColor.matches(field) -> issue(fp, text(R.string.ui_config_accent_format))
                    accent?.preset != null ->
                        issue(fp, text(R.string.ui_config_accent_both), UiIssueKind.Ignored)
                    else -> {
                        val color = UiAccent(rgb = field.substring(1).toInt(16))
                        entry(UiSettingKey.ACCENT, fp, color.json)
                        return config.copy(accent = color)
                    }
                }
            else -> issue(fp, text(R.string.ui_config_unknown_key), UiIssueKind.Ignored)
        }
        return config
    }

    // ── actions ──────────────────────────────────────────────────────────────────────────────

    fun action(value: Any?, pointer: String): UiAction? {
        val map = obj(value, pointer) ?: return null
        val tp = child(pointer, "type")
        if (!map.containsKey("type")) {
            issue(tp, text(R.string.ui_config_missing, "type"))
            return null
        }
        val type =
            choice(map["type"], tp, schema.actionTypes, R.string.ui_config_action_noun)
                ?: return null
        fun required(key: String): Any? {
            val p = child(pointer, key)
            if (!map.containsKey(key)) {
                issue(p, text(R.string.ui_config_missing, key))
                return Missing
            }
            return map[key]
        }
        when (type) {
            "send" -> {
                unknownKeys(map, pointer, listOf("type", "text"))
                val p = child(pointer, "text")
                val raw = required("text")
                if (raw === Missing) return null
                if (raw !is String) {
                    issue(p, text(R.string.ui_config_not_string))
                    return null
                }
                val clean =
                    raw.replace("\r\n", "\n").replace("\r", "\n").replace(sendControl, "").trim()
                if (clean.isEmpty()) {
                    issue(p, text(R.string.ui_config_empty))
                    return null
                }
                if (clean.points() > schema.sendTextMax) {
                    issue(p, UiMessage.Count(R.plurals.ui_config_too_long, schema.sendTextMax))
                    return null
                }
                return UiAction.Send(clean)
            }
            "open" -> {
                unknownKeys(map, pointer, listOf("type", "page"))
                val raw = required("page")
                if (raw === Missing) return null
                return choice(
                        raw,
                        child(pointer, "page"),
                        schema.pages,
                        R.string.ui_config_page_noun,
                    )
                    ?.let(UiAction::Open)
            }
            "command" -> {
                unknownKeys(map, pointer, listOf("type", "name", "args"))
                val p = child(pointer, "name")
                val raw = required("name")
                if (raw === Missing) return null
                if (raw !is String) {
                    issue(p, text(R.string.ui_config_not_string))
                    return null
                }
                var name = raw.trim()
                if (name.startsWith('/')) {
                    name = name.substring(1)
                    issue(p, text(R.string.ui_config_command_slash), UiIssueKind.Adjusted)
                }
                if (!commandName.matches(name)) {
                    issue(p, text(R.string.ui_config_command_name))
                    return null
                }
                var args: String? = null
                if (map.containsKey("args")) {
                    val ap = child(pointer, "args")
                    val rawArgs = map["args"]
                    if (rawArgs !is String) {
                        issue(ap, text(R.string.ui_config_not_string))
                        return null
                    }
                    if ('\n' in rawArgs || '\r' in rawArgs) {
                        issue(ap, text(R.string.ui_config_no_newline))
                        return null
                    }
                    val trimmed = rawArgs.replace(controlChars, "").trim()
                    if (trimmed.points() > schema.commandArgsMax) {
                        issue(
                            ap,
                            UiMessage.Count(R.plurals.ui_config_too_long, schema.commandArgsMax),
                        )
                        return null
                    }
                    if (trimmed.isNotEmpty()) args = trimmed
                }
                return UiAction.Command(name, args)
            }
            "link" -> {
                unknownKeys(map, pointer, listOf("type", "url"))
                val p = child(pointer, "url")
                val raw = required("url")
                if (raw === Missing) return null
                if (raw !is String) {
                    issue(p, text(R.string.ui_config_not_string))
                    return null
                }
                val url = raw.trim()
                if (!url.lowercase().startsWith("https://")) {
                    issue(p, text(R.string.ui_config_https_only))
                    return null
                }
                val normalized = "https://" + url.substring(8)
                val ok =
                    url.length <= schema.urlMax &&
                        httpsUrl.matches(normalized) &&
                        !badPercent.containsMatchIn(normalized) &&
                        runCatching { java.net.URI(normalized) }.isSuccess
                if (!ok) {
                    issue(p, text(R.string.ui_config_bad_url))
                    return null
                }
                return UiAction.Link(normalized)
            }
        }
        return null
    }

    // ── header ───────────────────────────────────────────────────────────────────────────────

    fun header(value: Any?, pointer: String): List<UiHeaderItem>? {
        val map = obj(value, pointer) ?: return null
        unknownKeys(map, pointer, listOf("actions"))
        if (!map.containsKey("actions")) return null
        val p = child(pointer, "actions")
        val list = array(map["actions"], p) ?: return null
        val out = mutableListOf<UiHeaderItem>()
        val seen = mutableSetOf<String>()
        var custom = 0
        list.forEachIndexed { index, item ->
            val ip = "$p/$index"
            when (item) {
                is String -> {
                    val id =
                        choice(item, ip, schema.headerBuiltins, R.string.ui_config_button_noun)
                            ?: return@forEachIndexed
                    if (!seen.add(id)) {
                        issue(ip, text(R.string.ui_config_duplicate_item), UiIssueKind.Ignored)
                        return@forEachIndexed
                    }
                    out += UiHeaderItem.Builtin(id)
                }
                is Map<*, *> -> {
                    val button =
                        customButton(item as Map<String, Any?>, ip, seen) ?: return@forEachIndexed
                    if (custom >= schema.headerCustomMax) {
                        issue(
                            ip,
                            UiMessage.Count(R.plurals.ui_config_too_many, schema.headerCustomMax),
                            UiIssueKind.Ignored,
                        )
                        return@forEachIndexed
                    }
                    custom++
                    seen += button.id
                    out += button
                }
                else -> issue(ip, text(R.string.ui_config_header_item))
            }
        }
        if (out.isEmpty() && list.isNotEmpty()) return null
        entry(UiSettingKey.HEADER_ACTIONS, p, JsonArray(out.map { it.json }))
        return out
    }

    fun customButton(
        map: Map<String, Any?>,
        pointer: String,
        taken: Set<String>,
    ): UiHeaderItem.Custom? {
        unknownKeys(map, pointer, listOf("id", "icon", "label", "action"))
        var ok = true
        var id: String? = null
        val idp = child(pointer, "id")
        val rawId = map["id"]
        when {
            !map.containsKey("id") -> issue(idp, text(R.string.ui_config_missing, "id"))
            rawId !is String -> issue(idp, text(R.string.ui_config_not_string))
            !headerId.matches(rawId) -> issue(idp, text(R.string.ui_config_header_id))
            rawId in taken || rawId in schema.headerBuiltins ->
                issue(idp, text(R.string.ui_config_id_taken))
            else -> id = rawId
        }
        if (id == null) ok = false
        var icon: String? = null
        val iconp = child(pointer, "icon")
        if (!map.containsKey("icon")) {
            issue(iconp, text(R.string.ui_config_missing, "icon"))
            ok = false
        } else {
            icon = choice(map["icon"], iconp, schema.icons, R.string.ui_config_icon_noun)
            if (icon == null) ok = false
        }
        var label: String? = null
        val labelp = child(pointer, "label")
        if (!map.containsKey("label")) {
            issue(labelp, text(R.string.ui_config_missing, "label"))
            ok = false
        } else {
            label = label(map["label"], labelp, schema.headerLabelMax)
            if (label == null) ok = false
        }
        var action: UiAction? = null
        val actionp = child(pointer, "action")
        if (!map.containsKey("action")) {
            issue(actionp, text(R.string.ui_config_missing, "action"))
            ok = false
        } else {
            action = action(map["action"], actionp)
            if (action == null) ok = false
        }
        if (!ok) return null
        return UiHeaderItem.Custom(id!!, icon!!, label!!, action!!)
    }

    // ── composer / empty state ───────────────────────────────────────────────────────────────

    fun quickActionList(value: Any?, pointer: String): List<UiQuickAction>? {
        val list = array(value, pointer) ?: return null
        val out = mutableListOf<UiQuickAction>()
        list.forEachIndexed { index, item ->
            val ip = "$pointer/$index"
            val map = obj(item, ip) ?: return@forEachIndexed
            unknownKeys(map, ip, listOf("label", "action"))
            val label = requiredLabel(map, ip, "label", schema.quickLabelMax)
            val action = requiredAction(map, ip)
            if (label == null || action == null) return@forEachIndexed
            if (out.size >= schema.quickActionsMax) {
                issue(
                    ip,
                    UiMessage.Count(R.plurals.ui_config_too_many, schema.quickActionsMax),
                    UiIssueKind.Ignored,
                )
                return@forEachIndexed
            }
            out += UiQuickAction(label, action)
        }
        if (out.isEmpty()) return null
        entry(UiSettingKey.QUICK_ACTIONS, pointer, JsonArray(out.map { it.json }))
        return out
    }

    fun suggestionList(value: Any?, pointer: String): List<UiSuggestion>? {
        val list = array(value, pointer) ?: return null
        val out = mutableListOf<UiSuggestion>()
        list.forEachIndexed { index, item ->
            val ip = "$pointer/$index"
            val map = obj(item, ip) ?: return@forEachIndexed
            unknownKeys(map, ip, listOf("title", "subtitle", "action"))
            val title = requiredLabel(map, ip, "title", schema.suggestionTitleMax)
            var subtitle: String? = null
            var ok = true
            if (map.containsKey("subtitle")) {
                subtitle =
                    label(map["subtitle"], child(ip, "subtitle"), schema.suggestionSubtitleMax)
                if (subtitle == null) ok = false
            }
            val action = requiredAction(map, ip)
            if (title == null || action == null || !ok) return@forEachIndexed
            if (out.size >= schema.suggestionsMax) {
                issue(
                    ip,
                    UiMessage.Count(R.plurals.ui_config_too_many, schema.suggestionsMax),
                    UiIssueKind.Ignored,
                )
                return@forEachIndexed
            }
            out += UiSuggestion(title, subtitle, action)
        }
        if (out.isEmpty()) return null
        entry(UiSettingKey.SUGGESTIONS, pointer, JsonArray(out.map { it.json }))
        return out
    }

    fun requiredLabel(map: Map<String, Any?>, pointer: String, key: String, max: Int): String? {
        val p = child(pointer, key)
        if (!map.containsKey(key)) {
            issue(p, text(R.string.ui_config_missing, key))
            return null
        }
        return label(map[key], p, max)
    }

    fun requiredAction(map: Map<String, Any?>, pointer: String): UiAction? {
        val p = child(pointer, "action")
        if (!map.containsKey("action")) {
            issue(p, text(R.string.ui_config_missing, "action"))
            return null
        }
        return action(map["action"], p)
    }

    // ── drawer / hide ────────────────────────────────────────────────────────────────────────

    fun groupList(value: Any?, pointer: String): List<String>? {
        val list = array(value, pointer) ?: return null
        val out = mutableListOf<String>()
        list.forEachIndexed { index, item ->
            val ip = "$pointer/$index"
            val group = choice(item, ip, schema.drawerGroups) ?: return@forEachIndexed
            if (group in out) {
                issue(ip, text(R.string.ui_config_duplicate_item), UiIssueKind.Ignored)
                return@forEachIndexed
            }
            out += group
        }
        if (out.isEmpty() && list.isNotEmpty()) return null
        entry(UiSettingKey.DRAWER_GROUPS, pointer, JsonArray(out.map(::JsonPrimitive)))
        return out
    }

    fun hideList(value: Any?, pointer: String): List<String> {
        val list = array(value, pointer) ?: return emptyList()
        val out = mutableListOf<String>()
        list.forEachIndexed { index, item ->
            val ip = "$pointer/$index"
            val hidden = choice(item, ip, schema.hideItems) ?: return@forEachIndexed
            if (hidden in out) {
                issue(ip, text(R.string.ui_config_duplicate_item), UiIssueKind.Ignored)
                return@forEachIndexed
            }
            out += hidden
            entry(UiSettingKey.hide(hidden), ip, JsonPrimitive(true))
        }
        return out
    }

    // ── shortcuts ────────────────────────────────────────────────────────────────────────────

    fun shortcutList(value: Any?, pointer: String): List<UiShortcut>? {
        val list = array(value, pointer) ?: return null
        val out = mutableListOf<UiShortcut>()
        val where = mutableMapOf<String, String>()
        list.forEachIndexed { index, item ->
            val ip = "$pointer/$index"
            val map = obj(item, ip) ?: return@forEachIndexed
            unknownKeys(map, ip, listOf("keys", "action"))
            val kp = child(ip, "keys")
            var keys: String? = null
            if (!map.containsKey("keys")) issue(kp, text(R.string.ui_config_missing, "keys"))
            else {
                keys = shortcutKeys(map["keys"], kp)
                if (keys != null && keys in where) {
                    issue(kp, text(R.string.ui_config_duplicate_shortcut, where.getValue(keys)))
                    keys = null
                }
            }
            val action = requiredAction(map, ip)
            if (keys == null || action == null) return@forEachIndexed
            if (out.size >= schema.shortcutsMax) {
                issue(
                    ip,
                    UiMessage.Count(R.plurals.ui_config_too_many, schema.shortcutsMax),
                    UiIssueKind.Ignored,
                )
                return@forEachIndexed
            }
            where[keys] = ip
            out += UiShortcut(keys, action)
        }
        if (out.isEmpty()) return null
        entry(UiSettingKey.SHORTCUTS, pointer, JsonArray(out.map { it.json }))
        return out
    }

    /** `Ctrl + Shift + N` → `ctrl+shift+n`; null (with an issue) when not usable. */
    fun shortcutKeys(value: Any?, pointer: String): String? {
        if (value !is String) {
            issue(pointer, text(R.string.ui_config_not_string))
            return null
        }
        val parts = value.lowercase().split('+').map { it.trim() }
        if (parts.any { it.isEmpty() }) {
            issue(pointer, text(R.string.ui_config_keys_format))
            return null
        }
        if (parts.any { it in schema.systemModifiers }) {
            issue(pointer, text(R.string.ui_config_system_key))
            return null
        }
        val key = parts.last()
        val mods = parts.dropLast(1)
        val modSet = mods.toSet()
        if (
            key !in schema.shortcutKeys ||
                modSet.size != mods.size ||
                !mods.all { it in listOf("ctrl", "alt", "shift") }
        ) {
            issue(pointer, text(R.string.ui_config_keys_format))
            return null
        }
        if (!functionKey.matches(key) && "ctrl" !in modSet && "alt" !in modSet) {
            issue(pointer, text(R.string.ui_config_need_modifier))
            return null
        }
        val canonical =
            (listOf("ctrl", "alt", "shift").filter { it in modSet } + key).joinToString("+")
        if (canonical in schema.reservedShortcuts) {
            issue(pointer, text(R.string.ui_config_reserved_key))
            return null
        }
        return canonical
    }

    // ── plugins ──────────────────────────────────────────────────────────────────────────────

    fun seatMap(value: Any?, pointer: String): Map<String, Map<String, String>> {
        val map = obj(value, pointer) ?: return emptyMap()
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for ((slot, packages) in map) {
            val sp = child(pointer, slot)
            if (!slotName.matches(slot)) {
                issue(sp, text(R.string.ui_config_slot_name))
                continue
            }
            if (slot !in schema.knownSlots)
                issue(sp, text(R.string.ui_config_unknown_slot), UiIssueKind.Adjusted)
            else if (slot in schema.unseatableSlots) {
                issue(sp, text(R.string.ui_config_unseatable_slot), UiIssueKind.Ignored)
                continue
            }
            val inner = obj(packages, sp) ?: continue
            for ((pkg, mode) in inner) {
                val pp = child(sp, pkg)
                if (pkg.length > 214 || !packageName.matches(pkg)) {
                    issue(pp, text(R.string.ui_config_package_name))
                    continue
                }
                val name = choice(mode, pp, schema.seatModes) ?: continue
                out.getOrPut(slot) { LinkedHashMap() }[pkg] = name
                entry(UiSettingKey.seat(slot, pkg), pp, JsonPrimitive(name))
            }
        }
        return out
    }

    private object Missing
}
