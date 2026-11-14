package dev.fughetta.dshcompose.ui.dynamicui

import kotlin.math.roundToInt
import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

/** Maps genui component JSON to native nodes under a shared [GenuiBudget]. */
internal class GenuiMapper(private val budget: GenuiBudget) {
    internal fun nodes(
        value: JsonElement?,
        path: String,
        depth: Int,
        maximum: Int = 32,
    ): PersistentList<UiNode> {
        val array = value as? JsonArray ?: return persistentListOf()
        val result = mutableListOf<UiNode>()
        for ((index, item) in array.withIndex()) {
            if (result.size >= maximum || budget.count >= 120) {
                budget.note(path, UiIssueKind.Truncated)
                break
            }
            val node = node(item, "$path[$index]", depth) ?: continue
            if (node is UiWebOnly && (result.lastOrNull() as? UiWebOnly)?.kind == node.kind) {
                budget.count--
                continue
            }
            result.add(node)
        }
        return result.toPersistentList()
    }

    private fun text(
        path: String,
        value: String,
        muted: Boolean = false,
        appearance: UiTextStyle = UiTextStyle(),
    ): UiText {
        val shown = budget.clipped(value, 2000, path).let(::genuiHighlight)
        return UiText(path, shown, muted, RestrictedText.parse(shown), appearance)
    }

    private fun extraText(
        path: String,
        value: String?,
        depth: Int,
        muted: Boolean = false,
        style: UiTextStyle = UiTextStyle(),
    ): UiText? {
        if (value == null || !budget.allocate(path, depth)) return null
        return text(path, value, muted, style)
    }

    private fun web(path: String, kind: UiWebKind): UiNode {
        budget.note(path, UiIssueKind.WebOnly)
        return UiWebOnly(path, kind)
    }

    private fun core(path: String, raw: JsonObject): UiNode? {
        val (node, notes) = DynamicUiParser.component(raw, path)
        for (issue in notes) budget.note(issue.path, issue.kind)
        return node.takeUnless { it is UiPlaceholder }
    }

    private fun node(value: JsonElement, path: String, depth: Int): UiNode? {
        if (!budget.allocate(path, depth)) return null
        val result = map(value, path, depth)
        if (result == null) budget.count--
        return result
    }

    private fun map(value: JsonElement, path: String, depth: Int): UiNode? {
        if (value is JsonPrimitive && value.isString) return genuiStr(value)?.let { text(path, it) }
        val raw =
            value as? JsonObject
                ?: run {
                    budget.note(path, UiIssueKind.Value)
                    return null
                }
        val type =
            genuiStr(raw["type"])
                ?: run {
                    budget.note(path, UiIssueKind.Required)
                    return null
                }
        return when (type) {
            "button" ->
                genuiStr(raw["label"])?.let {
                    UiGenuiButton(
                        path,
                        clip(it, 200),
                        when (genuiStr(raw["tone"])) {
                            "primary" -> GenuiButtonStyle.Primary
                            "danger" -> GenuiButtonStyle.Danger
                            "ghost" -> GenuiButtonStyle.Ghost
                            else -> GenuiButtonStyle.Tonal
                        },
                        genuiAction(raw),
                    )
                }
            "checkbox",
            "switch" ->
                genuiStr(raw["label"])?.let {
                    UiGenuiToggle(
                        path,
                        clip(it, 2000),
                        type == "switch",
                        (raw["checked"] as? JsonPrimitive)?.booleanOrNull == true,
                        genuiAction(raw),
                        if (type == "checkbox") genuiKey(raw["group"]) else null,
                    )
                }
            "select",
            "radio",
            "segmented-control" -> pick(raw, type, path)
            "choices" -> choices(raw, path)
            "quiz" -> quiz(raw, path)
            "input",
            "textarea",
            "editor" ->
                if (genuiStr(raw["inputType"]) == "password") web(path, UiWebKind.Interactive)
                else
                    UiGenuiField(
                        path,
                        type,
                        genuiStr(raw["label"])?.let { clip(it, 200) },
                        genuiStr(raw["placeholder"])?.let { clip(it, 200) },
                        genuiRawString(raw["value"])
                            ?.let {
                                budget.clipped(it, if (type == "editor") 12000 else 2000, path)
                            }
                            .orEmpty(),
                        genuiStr(raw["inputType"]) == "number",
                        if (type == "editor") null else genuiAction(raw),
                        genuiKey(raw["id"]),
                    )
            "slider" -> slider(raw, path)
            "submit" ->
                genuiStr(raw["label"])?.let {
                    UiGenuiSubmit(
                        path,
                        clip(it, 200),
                        genuiAction(raw),
                        genuiAction(raw, "resetAction"),
                        (raw["groups"] as? JsonArray)
                            ?.mapNotNull(::genuiKey)
                            ?.distinct()
                            ?.take(50)
                            ?.toPersistentList(),
                    )
                }
            "text" -> {
                val content = genuiStr(raw["content"] ?: raw["text"]) ?: return null
                val size =
                    when (genuiStr(raw["size"])) {
                        "display" -> UiTextSize.Display
                        "h1",
                        "h2",
                        "h3" -> UiTextSize.Heading
                        "caption",
                        "muted" -> UiTextSize.Caption
                        else ->
                            if ((raw["mono"] as? JsonPrimitive)?.booleanOrNull == true)
                                UiTextSize.Mono
                            else UiTextSize.Body
                    }
                val align =
                    when (genuiStr(raw["align"])) {
                        "center" -> UiTextAlign.Center
                        "right" -> UiTextAlign.End
                        else ->
                            if ((raw["center"] as? JsonPrimitive)?.booleanOrNull == true)
                                UiTextAlign.Center
                            else UiTextAlign.Start
                    }
                text(
                    path,
                    content,
                    genuiStr(raw["tone"]) == "muted" || size == UiTextSize.Caption,
                    UiTextStyle(
                        size,
                        genuiStr(raw["size"]) in setOf("display", "h1", "h2", "h3"),
                        genuiTone(raw["tone"]),
                        align,
                    ),
                )
            }
            "row",
            "col",
            "grid",
            "card",
            "carousel" -> container(raw, type, path, depth)
            "hero" -> hero(raw, path, depth)
            "stat" -> metric(raw, path)
            "badge" ->
                genuiStr(raw["label"])?.let { UiTag(path, clip(it, 200), genuiTone(raw["tone"])) }
            "avatar" -> genuiStr(raw["name"])?.let { UiTag(path, clip(it, 200)) }
            "progress" ->
                genuiNumber(raw["value"])?.let {
                    UiProgress(
                        path,
                        (it / 100).coerceIn(0.0, 1.0).toFloat(),
                        genuiStr(raw["label"])?.let { clip(it, 200) },
                        genuiStr(raw["valueLabel"])?.let { clip(it, 200) },
                        genuiStr(raw["variant"]) == "ring",
                    )
                }
            "list" -> list(raw, path, false)
            "timeline" -> list(raw, path, true)
            "table" -> table(raw, path)
            "keyvalue" -> {
                val pairs = raw["pairs"] as? JsonArray ?: return null
                val rows =
                    budget
                        .limit(pairs, 24, path)
                        .mapNotNull { pair ->
                            val obj = pair as? JsonObject ?: return@mapNotNull null
                            genuiStr(obj["key"])?.let {
                                UiListLine(
                                    rich(it, path, 200),
                                    rich(genuiCell(obj["value"]), path, 200),
                                )
                            }
                        }
                        .toPersistentList()
                if (rows.isEmpty()) null else UiList(path, rows)
            }
            "steps" -> steps(raw, path)
            "callout" -> {
                val content = genuiStr(raw["content"] ?: raw["text"])
                val title = genuiStr(raw["title"])
                val children =
                    listOfNotNull(
                            extraText(
                                "$path.title",
                                title,
                                depth + 1,
                                style = UiTextStyle(bold = true, tone = genuiTone(raw["tone"])),
                            ),
                            extraText("$path.content", content, depth + 1),
                        )
                        .toPersistentList()
                if (children.isEmpty()) null else UiPanel(path, children, genuiTone(raw["tone"]))
            }
            "chart" -> chart(raw, path)
            "code" ->
                genuiRawString(raw["code"])?.let {
                    UiCode(
                        path,
                        budget.clipped(it, 8000, path),
                        genuiStr(raw["lang"])?.let { clip(it, 32) }.orEmpty(),
                    )
                }
            "json" ->
                UiCode(
                    path,
                    budget.clipped(
                        budget.pretty.encodeToString(
                            JsonElement.serializer(),
                            raw["value"] ?: JsonNull,
                        ),
                        8000,
                        path,
                    ),
                    "json",
                )
            "diff" -> diff(raw, path)
            "copy" -> {
                val body = genuiRawString(raw["text"]) ?: return null
                val code = UiCode("$path.code", budget.clipped(body, 8000, path), "")
                val label = genuiStr(raw["label"])
                if (label == null) code.copy(path = path)
                else {
                    val heading = extraText("$path.label", label, depth + 1, true)
                    if (budget.allocate(code.path, depth + 1))
                        UiLayout(path, false, listOfNotNull(heading, code).toPersistentList())
                    else heading
                }
            }
            "link" -> genuiStr(raw["label"] ?: raw["href"])?.let { text(path, it) }
            "breadcrumb" ->
                (raw["items"] as? JsonArray)
                    ?.mapNotNull(::genuiStr)
                    ?.joinToString(" › ")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { text(path, it, true) }
            "divider" -> {
                val label = extraText("$path.label", genuiStr(raw["label"]), depth + 1, true)
                if (label == null) UiDivider(path)
                else if (budget.allocate("$path.rule", depth + 1))
                    UiLayout(path, false, persistentListOf(UiDivider("$path.rule"), label))
                else label
            }
            "spacer" -> UiSpacer(path)
            "arrow",
            "icon" -> null
            "tabs",
            "accordion" -> sections(raw, type, path, depth)
            "image" -> image(raw, path)
            "image-group" -> {
                val label = genuiStr(raw["title"])
                val source =
                    (raw["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter {
                        genuiStr(it["src"], false)?.startsWith("data:image/") == true
                    }
                val pictures =
                    nodes(
                        JsonArray(
                            source.map { JsonObject(it + ("type" to JsonPrimitive("image"))) },
                        ),
                        "$path.items",
                        depth + if (label == null) 1 else 2,
                        24,
                    )
                if (pictures.isEmpty()) web(path, UiWebKind.Image)
                else {
                    val title =
                        extraText("$path.title", label, depth + 1, style = UiTextStyle(bold = true))
                    if (title == null) UiGrid(path, 2, pictures)
                    else if (budget.allocate("$path.gallery", depth + 1))
                        UiLayout(
                            path,
                            false,
                            persistentListOf(title, UiGrid("$path.gallery", 2, pictures)),
                        )
                    else title
                }
            }
            "audio" -> web(path, UiWebKind.Audio)
            "video" -> web(path, UiWebKind.Video)
            "echart",
            "plot" -> web(path, UiWebKind.Chart)
            "mermaid",
            "diagram",
            "svg",
            "blocks" -> web(path, UiWebKind.Diagram)
            "scene3d" -> web(path, UiWebKind.Scene)
            "file-tree" -> web(path, UiWebKind.Files)
            "map" -> web(path, UiWebKind.Map)
            else -> web(path, UiWebKind.Other)
        }
    }

    private fun options(value: JsonElement?, path: String, maximum: Int): PersistentList<String> =
        budget
            .limit((value as? JsonArray).orEmpty().mapNotNull { genuiStr(it) }, maximum, path)
            .map { clip(it, 2000) }
            .toPersistentList()

    /** A numeric `answer` names an option by index; otherwise it is the option label. */
    private fun answer(value: JsonElement?, options: List<String>): String? =
        (value as? JsonPrimitive)?.let {
            if (it.isString) genuiStr(it) else it.intOrNull?.let(options::getOrNull)
        }

    private fun pick(raw: JsonObject, type: String, path: String): UiNode? {
        val options = options(raw["options"] ?: raw["items"], "$path.options", 50)
        if (options.isEmpty()) return null
        val style =
            when (type) {
                "select" -> GenuiPickStyle.Select
                "radio" -> GenuiPickStyle.Radio
                else -> GenuiPickStyle.Segmented
            }
        val selected =
            if (style == GenuiPickStyle.Segmented)
                genuiStr(raw["value"])?.let(options::indexOf)?.takeIf { it >= 0 }
            else genuiNumber(raw["selected"])?.toInt()?.takeIf { it in options.indices }
        val radio = style == GenuiPickStyle.Radio
        return UiGenuiPick(
            path,
            style,
            genuiStr(raw["label"])?.let { clip(it, 200) },
            options,
            selected,
            genuiAction(raw),
            genuiKey(raw[if (radio) "group" else "id"]),
            if (radio) answer(raw["answer"], options) else null,
            if (radio) genuiStr(raw["explanation"])?.let { clip(it, 2000) } else null,
        )
    }

    private fun choices(raw: JsonObject, path: String): UiNode? {
        val items =
            budget
                .limit((raw["items"] as? JsonArray).orEmpty(), 8, "$path.items")
                .mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val label = genuiStr(obj["label"]) ?: return@mapNotNull null
                    UiGenuiChoice(
                        clip(label, 2000),
                        genuiStr(obj["hint"])?.let { clip(it, 2000) },
                        genuiStr(obj["meta"])?.let { clip(it, 200) },
                        (obj["recommended"] as? JsonPrimitive)?.booleanOrNull == true,
                        (obj["disabled"] as? JsonPrimitive)?.booleanOrNull == true,
                        genuiStr(obj["reason"])?.let { clip(it, 2000) },
                    )
                }
                .toPersistentList()
        if (items.isEmpty()) return null
        val multi = genuiStr(raw["mode"]) == "multi"
        val group = genuiKey(raw["group"])
        return UiGenuiChoices(
            path,
            genuiStr(raw["title"])?.let { clip(it, 200) },
            genuiStr(raw["prompt"])?.let { clip(it, 2000) },
            items,
            multi && group == null,
            if (multi && group == null)
                (genuiNumber(raw["max"])?.toInt() ?: items.size).coerceIn(1, items.size)
            else 1,
            genuiStr(raw["submit"])?.let { clip(it, 200) },
            genuiAction(raw),
            group,
            group?.let { answer(raw["answer"], items.map(UiGenuiChoice::label)) },
            group?.let { genuiStr(raw["explanation"])?.let { clip(it, 2000) } },
        )
    }

    private fun quiz(raw: JsonObject, path: String): UiNode? {
        val question = genuiStr(raw["question"] ?: raw["title"] ?: raw["text"]) ?: return null
        val source = (raw["options"] ?: raw["choices"] ?: raw["items"]) as? JsonArray
        val options =
            budget
                .limit(source.orEmpty(), 8, "$path.options")
                .mapNotNull { item ->
                    val obj = item as? JsonObject
                    val label = genuiStr(obj?.get("label") ?: item) ?: return@mapNotNull null
                    UiGenuiQuizOption(
                        clip(label, 2000),
                        (obj?.get("correct") as? JsonPrimitive)?.booleanOrNull == true,
                        genuiStr(obj?.get("feedback"))?.let { clip(it, 2000) },
                    )
                }
                .toPersistentList()
        if (options.isEmpty()) return null
        return UiGenuiQuiz(
            path,
            clip(question, 2000),
            options,
            genuiStr(raw["explanation"])?.let { clip(it, 2000) },
            genuiAction(raw),
        )
    }

    private fun slider(raw: JsonObject, path: String): UiNode {
        val min = genuiNumber(raw["min"]) ?: 0.0
        val max = (genuiNumber(raw["max"]) ?: 100.0).coerceAtLeast(min)
        val step = genuiNumber(raw["step"])?.takeIf { it > 0 } ?: 1.0
        return UiGenuiSlider(
            path,
            genuiStr(raw["label"])?.let { clip(it, 200) },
            min,
            max,
            step,
            (genuiNumber(raw["value"]) ?: min).coerceIn(min, max),
            genuiAction(raw),
            genuiKey(raw["id"]),
        )
    }

    private fun container(raw: JsonObject, type: String, path: String, depth: Int): UiNode? {
        if (type == "carousel") {
            val children = nodes(raw["items"], "$path.items", depth + 1, 8)
            return if (children.isEmpty()) null
            else
                UiBrowse(
                    path,
                    UiBrowseKind.Carousel,
                    children
                        .mapIndexed { index, child ->
                            UiPage("${index + 1}", persistentListOf(child))
                        }
                        .toPersistentList(),
                )
        }
        val headings =
            if (type == "card")
                listOfNotNull(
                    extraText(
                        "$path.eyebrow",
                        genuiStr(raw["eyebrow"]),
                        depth + 1,
                        true,
                        UiTextStyle(UiTextSize.Caption),
                    ),
                    extraText(
                        "$path.title",
                        genuiStr(raw["title"]),
                        depth + 1,
                        style = UiTextStyle(UiTextSize.Heading, true),
                    ),
                )
            else emptyList()
        val children = (headings + nodes(raw["items"], "$path.items", depth + 1)).toPersistentList()
        if (children.isEmpty()) return null
        if (type == "card") {
            val tint =
                genuiStr(raw["accent"])
                    ?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                    ?.removePrefix("#")
                    ?.toLong(16)
            return UiPanel(path, children, genuiTone(raw["tone"]), tint)
        }
        if (type == "grid")
            return UiGrid(path, (genuiNumber(raw["cols"])?.toInt() ?: 2).coerceIn(1, 4), children)
        val items = raw["items"] as? JsonArray
        val widths = items?.mapNotNull { item ->
            (item as? JsonObject)
                ?.takeIf { genuiStr(it["type"]) == "col" }
                ?.get("width")
                ?.let(::genuiNumber)
                ?.let { (it / 100 * 12).roundToInt().coerceIn(1, 12).toFloat() }
        }
        val weights =
            widths?.takeIf { it.size == children.size && it.size <= 8 }?.toPersistentList()
        return UiLayout(path, type == "row", children, weights)
    }

    private fun metric(raw: JsonObject, path: String, emptyLabel: Boolean = false): UiMetric? {
        val label = genuiStr(raw["label"]) ?: if (emptyLabel) "" else return null
        val value = genuiStr(raw["value"]) ?: return null
        val delta = genuiStr(raw["delta"])
        val tone =
            when {
                delta?.startsWith('+') == true -> UiTone.Good
                delta?.startsWith('-') == true || delta?.startsWith('−') == true -> UiTone.Bad
                else -> UiTone.Info
            }
        val spark = raw["spark"] as? JsonArray
        val points =
            spark
                ?.takeIf { it.size in 2..60 && it.all { v -> genuiNumber(v) != null } }
                ?.takeLast(30)
                ?.map { genuiNumber(it) }
                ?.toPersistentList()
        return UiMetric(
            path,
            clip(label, 200),
            clip(genuiNumber(raw["value"])?.let(::uiNumber) ?: value, 200),
            null,
            tone,
            delta?.let { clip(it, 200) },
            points,
            genuiStr(raw["size"]) == "hero",
        )
    }

    private fun hero(raw: JsonObject, path: String, depth: Int): UiNode? {
        val title = genuiStr(raw["title"])
        val value = genuiStr(raw["value"])
        val children = mutableListOf<UiNode>()
        if (value != null && budget.allocate("$path.value", depth + 1))
            metric(
                    JsonObject(
                        raw + ("label" to JsonPrimitive(genuiStr(raw["label"]) ?: title.orEmpty())),
                    ),
                    "$path.value",
                    true,
                )
                ?.let { children.add(it.copy(display = true)) }
        if (title != null && (value == null || genuiStr(raw["label"]) != null))
            extraText(
                    "$path.title",
                    title,
                    depth + 1,
                    style = UiTextStyle(UiTextSize.Display, true),
                )
                ?.let(children::add)
        extraText("$path.subtitle", genuiStr(raw["subtitle"]), depth + 1, true)?.let(children::add)
        return if (children.isEmpty()) null
        else UiPanel(path, children.toPersistentList(), genuiTone(raw["tone"]))
    }

    private fun rich(value: String, path: String, max: Int = 2000) =
        RestrictedText.parse(budget.clipped(value, max, path).let(::genuiHighlight))

    private fun list(raw: JsonObject, path: String, timeline: Boolean): UiNode? {
        val array = raw["items"] as? JsonArray ?: return null
        val items =
            budget
                .limit(array, 50, "$path.items")
                .mapNotNull { item ->
                    if (item is JsonPrimitive && item.isString)
                        return@mapNotNull genuiStr(item)?.let { UiListLine(rich(it, path), null) }
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val title =
                        listOf("title", "label", "name").firstNotNullOfOrNull { genuiStr(obj[it]) }
                    val desc =
                        (if (timeline) listOf("desc")
                            else listOf("desc", "description", "content", "text", "body", "detail"))
                            .firstNotNullOfOrNull { genuiStr(obj[it]) }
                    val shown = title ?: desc ?: return@mapNotNull null
                    val time = if (timeline) genuiStr(obj["time"]) else null
                    UiListLine(
                        rich(if (time == null) shown else "$time · $shown", path),
                        if (title != null && desc != null) rich(desc, path) else null,
                    )
                }
                .toPersistentList()
        return if (items.isEmpty()) null else UiList(path, items, timeline)
    }

    private fun table(raw: JsonObject, path: String): UiNode? {
        val columns = (raw["columns"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return null
        val sourceRows = raw["rows"] as? JsonArray ?: return null
        val kept = budget.limit(columns, 8, "$path.columns")
        val types = raw["types"] as? JsonArray
        val rows =
            budget
                .limit(sourceRows, 50, "$path.rows")
                .mapIndexedNotNull { index, row ->
                    if (row !is JsonArray) return@mapIndexedNotNull null
                    kept.indices
                        .map { column ->
                            rich(
                                if (genuiStr(types?.getOrNull(column)) == "index") "${index + 1}"
                                else genuiCell(row.getOrNull(column)),
                                path,
                                200,
                            )
                        }
                        .toPersistentList()
                }
                .toPersistentList()
        return UiTable(path, kept.map { rich(genuiCell(it), path, 200) }.toPersistentList(), rows)
    }

    private fun steps(raw: JsonObject, path: String): UiNode? {
        val array = (raw["steps"] ?: raw["items"]) as? JsonArray ?: return null
        val current = genuiNumber(raw["current"])?.toInt() ?: array.size
        val items =
            budget
                .limit(array, 12, "$path.steps")
                .mapIndexedNotNull { index, item ->
                    val obj = item as? JsonObject
                    val title =
                        genuiStr(obj?.get("title") ?: obj?.get("label") ?: item)
                            ?: return@mapIndexedNotNull null
                    UiStep(
                        rich(title, path, 200).text,
                        when {
                            index < current -> UiStepStatus.Done
                            index == current -> UiStepStatus.Current
                            else -> UiStepStatus.Todo
                        },
                        genuiStr(obj?.get("desc"))?.let { rich(it, path, 200).text },
                    )
                }
                .toPersistentList()
        return if (items.isEmpty()) null else UiSteps(path, items)
    }

    private fun chart(raw: JsonObject, path: String): UiNode {
        val sources =
            (raw["series"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.filter { it["data"] is JsonArray }
                ?.takeIf { it.isNotEmpty() }
                ?: (raw["data"] as? JsonArray)
                    ?.let { listOf(buildJsonObject { put("data", it) }) }
                    .orEmpty()
        val categories = linkedSetOf<String>()
        val kept = budget.limit(sources, 6, "$path.series")
        for (series in kept) for (point in series.getValue("data").jsonArray) (point as? JsonObject)
            ?.get("label")
            ?.let(::genuiStr)
            ?.let(categories::add)
        if (categories.isEmpty()) return web(path, UiWebKind.Chart)
        val labels = budget.limit(categories.toList(), 60, "$path.categories")
        val mapped = buildJsonObject {
            put("type", "chart")
            put(
                "kind",
                when (genuiStr(raw["kind"])) {
                    "line" -> "line"
                    "donut" -> "donut"
                    else ->
                        if ((raw["horizontal"] as? JsonPrimitive)?.booleanOrNull == true) "hbar"
                        else "bar"
                },
            )
            put("categories", JsonArray(labels.map(::JsonPrimitive)))
            put(
                "series",
                JsonArray(
                    kept.map { series ->
                        val index = linkedMapOf<String, JsonElement>()
                        for (point in series.getValue("data").jsonArray) if (point is JsonObject)
                            genuiStr(point["label"])?.let {
                                index.putIfAbsent(it, point["value"] ?: JsonNull)
                            }
                        buildJsonObject {
                            series["label"]?.let { put("name", it) }
                            put("values", JsonArray(labels.map { index[it] ?: JsonNull }))
                        }
                    },
                ),
            )
        }
        return core(path, mapped) ?: web(path, UiWebKind.Chart)
    }

    private fun sections(raw: JsonObject, type: String, path: String, depth: Int): UiNode? {
        val array = raw[if (type == "tabs") "tabs" else "items"] as? JsonArray ?: return null
        val pages =
            budget
                .limit(array, 8, path)
                .mapIndexedNotNull { index, item ->
                    val obj = item as? JsonObject ?: return@mapIndexedNotNull null
                    UiPage(
                        genuiStr(obj[if (type == "tabs") "label" else "title"])?.let {
                            clip(it, 200)
                        } ?: "${index + 1}",
                        nodes(obj["items"], "$path[$index].items", depth + 1),
                    )
                }
                .toPersistentList()
        return if (pages.isEmpty()) null
        else
            UiBrowse(path, if (type == "tabs") UiBrowseKind.Tabs else UiBrowseKind.Accordion, pages)
    }

    private fun image(raw: JsonObject, path: String): UiNode {
        val source = genuiStr(raw["src"], trim = false)
        if (source == null || !source.startsWith("data:image/")) return web(path, UiWebKind.Image)
        val accepted = inlinePicture(source)
        if (accepted == null) budget.note(path, UiIssueKind.Value)
        return UiPicture(
            path,
            accepted?.source,
            genuiStr(raw["alt"])?.let { clip(it, 200) }.orEmpty(),
            genuiStr(raw["caption"])?.let { clip(it, 200) },
            accepted?.let { it.width.toFloat() / it.height },
        )
    }

    private fun diff(raw: JsonObject, path: String): UiNode? {
        val diffs = raw["diffs"] as? JsonArray ?: return null
        val code = buildString {
            for (item in diffs) if (item is JsonObject) {
                if (isNotEmpty()) append('\n')
                append("--- ").append(genuiStr(item["path"]).orEmpty()).append('\n')
                for ((key, prefix) in listOf("oldText" to '-', "newText" to '+')) genuiRawString(
                        item[key],
                    )
                    ?.lineSequence()
                    ?.toList()
                    ?.let { lines -> if (lines.lastOrNull() == "") lines.dropLast(1) else lines }
                    ?.forEach { line -> append(prefix).append(line).append('\n') }
            }
        }
            .trimEnd()
        return if (code.isEmpty()) null else UiCode(path, budget.clipped(code, 8000, path), "diff")
    }
}
