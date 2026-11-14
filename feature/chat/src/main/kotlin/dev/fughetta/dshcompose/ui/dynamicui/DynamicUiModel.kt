// Portions derived from DS Bot (https://github.com/Fei2411/DeepSeek-Bot), Apache-2.0,
// Copyright 2026 Fei2411 and the DS Bot contributors. See THIRD_PARTY_NOTICES.md.
// Modified: Kotlin immutable models, bounded JSON parsing and native component normalization.
package dev.fughetta.dshcompose.ui.dynamicui

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.collections.immutable.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal enum class UiIssueKind {
    Json,
    Size,
    Depth,
    Required,
    Unknown,
    Truncated,
    Value,
    WebOnly,
}

internal data class UiIssue(val path: String, val kind: UiIssueKind)

internal enum class UiTone {
    Info,
    Good,
    Warn,
    Bad,
    Muted,
}

internal enum class UiChartKind {
    Bar,
    Horizontal,
    Line,
    Donut,
}

internal enum class UiStepStatus {
    Done,
    Current,
    Todo,
    Failed,
}

internal sealed interface UiNode {
    val path: String
}

internal data class UiLayout(
    override val path: String,
    val row: Boolean,
    val children: PersistentList<UiNode>,
    val weights: PersistentList<Float>? = null,
) : UiNode

internal enum class UiTextSize {
    Body,
    Caption,
    Heading,
    Display,
    Mono,
}

internal enum class UiTextAlign {
    Start,
    Center,
    End,
}

internal data class UiTextStyle(
    val size: UiTextSize = UiTextSize.Body,
    val bold: Boolean = false,
    val tone: UiTone = UiTone.Info,
    val align: UiTextAlign = UiTextAlign.Start,
)

internal data class UiText(
    override val path: String,
    val text: String,
    val muted: Boolean = false,
    val rich: UiRichText? = null,
    val appearance: UiTextStyle = UiTextStyle(),
) : UiNode

internal data class UiMetric(
    override val path: String,
    val label: String,
    val value: String,
    val boolean: Boolean?,
    val tone: UiTone,
    val caption: String?,
    val trend: PersistentList<Double?>? = null,
    val display: Boolean = false,
) : UiNode

internal data class UiSeries(val name: String?, val values: PersistentList<Double?>)

internal data class UiChart(
    override val path: String,
    val kind: UiChartKind,
    val categories: PersistentList<String>,
    val series: PersistentList<UiSeries>,
    val unit: String?,
    val caption: String?,
) : UiNode

internal data class UiComparison(
    val title: String,
    val badge: String?,
    val rows: PersistentList<Pair<String, String>>,
)

internal data class UiCompare(override val path: String, val items: PersistentList<UiComparison>) :
    UiNode

internal data class UiStep(val label: String, val status: UiStepStatus, val detail: String?)

internal data class UiSteps(override val path: String, val items: PersistentList<UiStep>) : UiNode

internal data class UiCallout(override val path: String, val text: String, val tone: UiTone) :
    UiNode

internal data class UiOption(val label: String, val description: String?)

internal data class UiOptions(
    override val path: String,
    val options: PersistentList<UiOption>,
    val multi: Boolean,
    val freetext: Boolean,
    val hint: String?,
) : UiNode

internal data class UiConfirm(
    override val path: String,
    val confirm: String?,
    val cancel: String?,
) : UiNode

internal data class UiPlaceholder(override val path: String, val issue: UiIssue) : UiNode

internal data class UiPanel(
    override val path: String,
    val children: PersistentList<UiNode>,
    val tone: UiTone? = null,
    val tint: Long? = null,
) : UiNode

internal data class UiGrid(
    override val path: String,
    val columns: Int,
    val children: PersistentList<UiNode>,
) : UiNode

internal enum class UiBrowseKind {
    Tabs,
    Accordion,
    Carousel,
}

internal data class UiPage(val label: String, val children: PersistentList<UiNode>)

internal data class UiBrowse(
    override val path: String,
    val kind: UiBrowseKind,
    val pages: PersistentList<UiPage>,
) : UiNode

internal data class UiListLine(val title: UiRichText, val subtitle: UiRichText?)

internal data class UiList(
    override val path: String,
    val items: PersistentList<UiListLine>,
    val timeline: Boolean = false,
) : UiNode

internal data class UiTable(
    override val path: String,
    val columns: PersistentList<UiRichText>,
    val rows: PersistentList<PersistentList<UiRichText>>,
) : UiNode

internal data class UiProgress(
    override val path: String,
    val fraction: Float,
    val label: String?,
    val caption: String?,
    val ring: Boolean,
) : UiNode

internal data class UiCode(override val path: String, val code: String, val language: String) :
    UiNode

internal data class UiPicture(
    override val path: String,
    val source: String?,
    val alt: String,
    val caption: String?,
    val aspect: Float? = null,
) : UiNode {
    override fun toString() = "UiPicture(path=$path)"
}

internal data class UiTag(
    override val path: String,
    val text: String,
    val tone: UiTone = UiTone.Info,
) : UiNode

internal enum class UiWebKind {
    Interactive,
    Chart,
    Diagram,
    Image,
    Audio,
    Video,
    Scene,
    Files,
    Map,
    Other,
}

internal data class UiWebOnly(override val path: String, val kind: UiWebKind) : UiNode

internal data class UiDivider(override val path: String) : UiNode

internal data class UiSpacer(override val path: String) : UiNode

internal data class UiView(
    val title: String?,
    val root: UiNode,
    val issues: PersistentList<UiIssue>,
    val dialect: String? = null,
)

internal data class UiParse(
    val view: UiView?,
    val issues: PersistentList<UiIssue>,
    val dialect: String? = null,
)

internal object DynamicUiParser {
    const val MaxBytes = 16384
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    fun parse(source: String): UiParse {
        fun invalid(kind: UiIssueKind) = UiParse(null, persistentListOf(UiIssue("view", kind)))
        if (source.length > MaxBytes || source.toByteArray(Charsets.UTF_8).size > MaxBytes)
            return invalid(UiIssueKind.Size)
        if (jsonTooDeep(source)) return invalid(UiIssueKind.Depth)
        val decoded =
            try {
                json.parseToJsonElement(source)
            } catch (_: SerializationException) {
                return invalid(UiIssueKind.Json)
            }
        if (isGenui(decoded)) return GenuiReader().parse(decoded)
        val root =
            unpack(decoded).let {
                if (it is JsonArray)
                    buildJsonObject {
                        put("type", "stack")
                        put("children", it)
                    }
                else it
            }
        val context = Reader()
        val node = context.node(root, "view", 1)
        val issues = context.issues.toPersistentList()
        val invalid =
            node is UiPlaceholder || node is UiLayout && node.children.all { it is UiPlaceholder }
        return UiParse(
            if (invalid) null
            else UiView((root as? JsonObject)?.get("title").label(80), node, issues),
            issues,
        )
    }

    internal fun component(value: JsonObject, path: String): Pair<UiNode, List<UiIssue>> {
        val reader = Reader()
        return reader.node(value, path, 1) to reader.issues
    }

    private class Reader {
        val issues = mutableListOf<UiIssue>()
        private var nodes = 0

        private fun note(path: String, kind: UiIssueKind) {
            if (issues.size < 16) issues.add(UiIssue(path, kind))
        }

        private fun broken(path: String, kind: UiIssueKind = UiIssueKind.Required): UiPlaceholder {
            val issue = UiIssue(path, kind)
            note(path, kind)
            return UiPlaceholder(path, issue)
        }

        private fun <T> limited(values: List<T>, max: Int, path: String): List<T> {
            if (values.size > max) note(path, UiIssueKind.Truncated)
            return values.take(max)
        }

        private fun text(value: JsonElement?, max: Int, path: String): String? {
            val original = value.scalar() ?: return null
            if (original.length > max) note(path, UiIssueKind.Truncated)
            return clip(original, max)
        }

        fun node(value: JsonElement, path: String, depth: Int): UiNode {
            nodes++
            if (depth > 6) return broken(path, UiIssueKind.Depth)
            val raw = value as? JsonObject ?: return broken(path)
            val type = raw["type"].scalar()
            return when (type) {
                "stack",
                "row" -> {
                    val children =
                        array(raw["children"])?.takeIf { it.isNotEmpty() }
                            ?: return broken("$path.children")
                    val built = mutableListOf<UiNode>()
                    for ((index, child) in limited(children, 16, "$path.children").withIndex()) {
                        if (nodes >= 40) {
                            note("$path.children", UiIssueKind.Truncated)
                            break
                        }
                        built.add(node(child, "$path.children[$index]", depth + 1))
                    }
                    UiLayout(path, type == "row", built.toPersistentList())
                }
                "text" ->
                    UiText(
                        path,
                        text(raw["text"], 2000, "$path.text") ?: return broken("$path.text"),
                        raw["tone"].scalar() == "muted",
                    )
                "metric" -> {
                    val label = raw["label"].label(200) ?: return broken("$path.label")
                    val value = raw["value"] as? JsonPrimitive ?: return broken("$path.value")
                    if (value is JsonNull) return broken("$path.value")
                    val boolean = value.booleanOrNull.takeUnless { value.isString }
                    val shown =
                        value.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::uiNumber)
                            ?: value.content
                    UiMetric(
                        path,
                        label,
                        clip(shown, 200),
                        boolean,
                        tone(raw["tone"]),
                        raw["caption"].label(200),
                    )
                }
                "chart" -> chart(raw, path)
                "compare" -> compare(raw, path)
                "steps" -> steps(raw, path)
                "callout" ->
                    UiCallout(
                        path,
                        text(raw["text"], 2000, "$path.text") ?: return broken("$path.text"),
                        tone(raw["tone"]),
                    )
                "options" -> options(raw, path)
                "confirm" ->
                    UiConfirm(path, raw["confirmLabel"].label(12), raw["cancelLabel"].label(12))
                else -> broken("$path.type", UiIssueKind.Unknown)
            }
        }

        private fun chart(raw: JsonObject, path: String): UiNode {
            val kind =
                when (raw["kind"].scalar()) {
                    null,
                    "bar",
                    "column",
                    "columns" -> UiChartKind.Bar
                    "hbar",
                    "barh",
                    "horizontal",
                    "horizontalBar" -> UiChartKind.Horizontal
                    "line",
                    "area",
                    "spline" -> UiChartKind.Line
                    "donut",
                    "pie",
                    "doughnut",
                    "ring" -> UiChartKind.Donut
                    else -> {
                        note("$path.kind", UiIssueKind.Unknown)
                        UiChartKind.Bar
                    }
                }
            val categories =
                array(raw["categories"])?.takeIf { it.isNotEmpty() }
                    ?: return broken("$path.categories")
            val labels =
                limited(categories, 60, "$path.categories")
                    .map { it.label(40).orEmpty() }
                    .toPersistentList()
            val candidate = unpack(raw["series"] ?: raw["values"] ?: JsonNull)
            val rawSeries =
                when (candidate) {
                    is JsonObject -> listOf(candidate)
                    is JsonArray ->
                        if (candidate.all { it is JsonPrimitive }) listOf(candidate) else candidate
                    else -> return broken("$path.series")
                }
            val series = mutableListOf<UiSeries>()
            val seriesLimit = if (kind == UiChartKind.Donut) 1 else 6
            for (item in rawSeries) {
                if (series.size >= seriesLimit) {
                    note("$path.series", UiIssueKind.Truncated)
                    break
                }
                val values = array((item as? JsonObject)?.get("values") ?: item)
                if (values == null) {
                    note("$path.series", UiIssueKind.Required)
                    continue
                }
                if (values.size != categories.size) note("$path.series", UiIssueKind.Value)
                var invalid = false
                val points =
                    labels.indices
                        .map { index ->
                            values.getOrNull(index).let { value ->
                                chartValue(value).also {
                                    if (it == null && value != null && value !is JsonNull)
                                        invalid = true
                                }
                            }
                        }
                        .toPersistentList()
                if (invalid) note("$path.series", UiIssueKind.Value)
                series.add(UiSeries((item as? JsonObject)?.get("name").label(40), points))
            }
            if (series.none { seriesItem -> seriesItem.values.any { it != null } })
                return broken("$path.series", UiIssueKind.Value)
            return UiChart(
                path,
                kind,
                labels,
                series.toPersistentList(),
                raw["unit"].label(12),
                raw["caption"].label(200),
            )
        }

        private fun compare(raw: JsonObject, path: String): UiNode {
            val rawItems = array(raw["items"]) ?: return broken("$path.items")
            val items =
                limited(rawItems, 4, "$path.items")
                    .mapNotNull { item ->
                        val value =
                            item as? JsonObject
                                ?: run {
                                    note("$path.items", UiIssueKind.Required)
                                    return@mapNotNull null
                                }
                        val title =
                            value["title"].label(40)
                                ?: run {
                                    note("$path.items.title", UiIssueKind.Required)
                                    return@mapNotNull null
                                }
                        val rawRows = unpack(value["rows"] ?: JsonNull)
                        val rows =
                            when (rawRows) {
                                is JsonArray -> rawRows
                                is JsonObject ->
                                    rawRows.map {
                                        JsonArray(listOf(JsonPrimitive(it.key), it.value))
                                    }
                                else -> emptyList()
                            }
                        val pairs =
                            limited(rows, 8, "$path.items.rows")
                                .mapNotNull { row ->
                                    when (row) {
                                        is JsonArray ->
                                            row.firstOrNull().label(40)?.let {
                                                it to row.getOrNull(1).label(80).orEmpty()
                                            }
                                        is JsonObject ->
                                            (row["label"] ?: row["name"]).label(40)?.let {
                                                it to row["value"].label(80).orEmpty()
                                            }
                                        else -> null
                                    }
                                }
                                .toPersistentList()
                        UiComparison(title, value["badge"].label(12), pairs)
                    }
                    .toPersistentList()
            return if (items.isEmpty()) broken("$path.items") else UiCompare(path, items)
        }

        private fun steps(raw: JsonObject, path: String): UiNode {
            val rawItems = array(raw["items"]) ?: return broken("$path.items")
            val items =
                limited(rawItems, 12, "$path.items")
                    .mapNotNull { item ->
                        if (item is JsonPrimitive && item !is JsonNull)
                            return@mapNotNull UiStep(
                                item.label(200).orEmpty(),
                                UiStepStatus.Todo,
                                null,
                            )
                        val value =
                            item as? JsonObject
                                ?: run {
                                    note("$path.items", UiIssueKind.Required)
                                    return@mapNotNull null
                                }
                        val label =
                            (value["label"] ?: value["title"]).label(200)
                                ?: run {
                                    note("$path.items.label", UiIssueKind.Required)
                                    return@mapNotNull null
                                }
                        val status =
                            when (value["status"].scalar()) {
                                "done",
                                "completed",
                                "complete",
                                "success" -> UiStepStatus.Done
                                "current",
                                "active",
                                "doing",
                                "in_progress",
                                "running" -> UiStepStatus.Current
                                "failed",
                                "error",
                                "blocked" -> UiStepStatus.Failed
                                null,
                                "todo",
                                "pending",
                                "waiting" -> UiStepStatus.Todo
                                else -> {
                                    note("$path.items.status", UiIssueKind.Unknown)
                                    UiStepStatus.Todo
                                }
                            }
                        UiStep(label, status, value["detail"].label(200))
                    }
                    .toPersistentList()
            return if (items.isEmpty()) broken("$path.items") else UiSteps(path, items)
        }

        private fun options(raw: JsonObject, path: String): UiNode {
            val rawOptions = array(raw["options"]) ?: return broken("$path.options")
            val seen = mutableSetOf<String>()
            val options = rawOptions.mapNotNull { item ->
                val obj = item as? JsonObject
                val label =
                    (obj?.get("label") ?: item).label(80)
                        ?: run {
                            note("$path.options.label", UiIssueKind.Required)
                            return@mapNotNull null
                        }
                if (!seen.add(label)) {
                    note("$path.options", UiIssueKind.Value)
                    return@mapNotNull null
                }
                UiOption(label, obj?.get("description").label(200))
            }
            if (options.isEmpty()) return broken("$path.options")
            val hint =
                (raw["freetext"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeUnless { it == "true" }
                    ?.let { clip(it.trim(), 200) }
                    ?.takeIf { it.isNotEmpty() }
            return UiOptions(
                path,
                limited(options, 8, "$path.options").toPersistentList(),
                raw["multi"].scalar() == "true",
                raw["freetext"].scalar() == "true" || hint != null,
                hint,
            )
        }
    }
}

private val genuiTypes =
    setOf(
        "col",
        "grid",
        "card",
        "carousel",
        "divider",
        "spacer",
        "arrow",
        "hero",
        "stat",
        "badge",
        "progress",
        "list",
        "table",
        "keyvalue",
        "avatar",
        "image",
        "image-group",
        "audio",
        "video",
        "timeline",
        "file-tree",
        "breadcrumb",
        "diff",
        "json",
        "code",
        "icon",
        "blocks",
        "plot",
        "echart",
        "button",
        "input",
        "select",
        "checkbox",
        "radio",
        "switch",
        "slider",
        "textarea",
        "tabs",
        "accordion",
        "copy",
        "link",
        "submit",
        "svg",
        "mermaid",
        "diagram",
        "scene3d",
        "quiz",
        "map",
        "choices",
        "segmented-control",
        "editor",
        "date-picker",
    )

private fun isGenui(value: JsonElement): Boolean {
    if (value is JsonArray)
        return value.any { (it as? JsonObject)?.get("type").scalar() in genuiTypes }
    val raw = value as? JsonObject ?: return false
    val type = raw["type"].scalar()
    if (type == null) return raw["items"] is JsonArray
    if (type in genuiTypes) return true
    return when (type) {
        "text",
        "callout" ->
            (raw["content"] as? JsonPrimitive)?.isString == true &&
                (raw["text"] == null || raw["text"] is JsonNull)
        "row" ->
            raw["items"] is JsonArray && (raw["children"] == null || raw["children"] is JsonNull)
        "steps" -> raw["steps"] is JsonArray && (raw["items"] == null || raw["items"] is JsonNull)
        "chart" ->
            raw["data"] is JsonArray && (raw["categories"] == null || raw["categories"] is JsonNull)
        else -> false
    }
}

private fun unpack(value: JsonElement): JsonElement {
    var current = value
    while (current is JsonObject && current.size == 1 && "item" in current) {
        val next = current.getValue("item")
        if (next !is JsonArray && next !is JsonObject) break
        current = next
    }
    return if (current is JsonArray) current else value
}

private fun array(value: JsonElement?): List<JsonElement>? = value?.let(::unpack) as? JsonArray

private fun JsonElement?.scalar(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonElement?.label(max: Int): String? =
    scalar()?.trim()?.takeIf { it.isNotEmpty() }?.let { clip(it, max) }

private fun tone(raw: JsonElement?): UiTone =
    when (raw.scalar()) {
        "good",
        "success",
        "positive",
        "tip" -> UiTone.Good
        "warn",
        "warning",
        "caution" -> UiTone.Warn
        "bad",
        "danger",
        "error",
        "negative" -> UiTone.Bad
        "muted",
        "secondary" -> UiTone.Muted
        else -> UiTone.Info
    }

internal fun clip(value: String, limit: Int): String =
    if (value.length <= limit) value
    else value.substring(0, if (value[limit - 1].isHighSurrogate()) limit - 1 else limit) + "…"

internal fun chartValue(value: JsonElement?): Double? =
    value
        .scalar()
        ?.replace(",", "")
        ?.replace("_", "")
        ?.removeSuffix("%")
        ?.trim()
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }

internal fun uiNumber(value: Double): String {
    if (kotlin.math.abs(value) >= 1e21)
        return BigDecimal.valueOf(value)
            .round(java.math.MathContext(3, RoundingMode.HALF_UP))
            .stripTrailingZeros()
            .toString()
    val plain =
        BigDecimal.valueOf(value)
            .setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    if (kotlin.math.abs(value) < 10000) return plain
    val parts = plain.split('.', limit = 2)
    val sign = if (parts[0].startsWith('-')) "-" else ""
    return sign +
        parts[0].removePrefix("-").reversed().chunked(3).joinToString(",").reversed() +
        if (parts.size > 1) ".${parts[1]}" else ""
}

private fun jsonTooDeep(source: String): Boolean {
    var depth = 0
    var string = false
    var escaped = false
    var index = 0
    while (index < source.length) {
        val char = source[index]
        if (string) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true else if (char == '"') string = false
        } else
            when {
                char == '"' -> string = true
                source.startsWith("//", index) -> {
                    index =
                        source.indexOf('\n', index + 2).let { if (it < 0) source.length else it }
                    continue
                }
                source.startsWith("/*", index) -> {
                    index =
                        source.indexOf("*/", index + 2).let {
                            if (it < 0) source.length else it + 2
                        }
                    continue
                }
                char == '{' || char == '[' -> {
                    depth++
                    if (depth > 64) return true
                }
                char == '}' || char == ']' -> depth--
            }
        index++
    }
    return false
}
