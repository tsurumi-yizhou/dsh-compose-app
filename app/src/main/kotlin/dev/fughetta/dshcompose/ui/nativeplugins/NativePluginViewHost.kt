package dev.fughetta.dshcompose.ui.nativeplugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.R
import dev.fughetta.dshcompose.domain.nativeplugins.*
import dev.fughetta.dshcompose.ui.appearance.LocalAppearanceCodeFont
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.phone.PerformanceAnimatedVisibility
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
fun NativePluginView(
    view: JsonElement,
    data: JsonElement,
    enabled: Boolean,
    onAction: (NativePluginViewAction) -> Unit,
    budget: NativePluginViewBudget = NativePluginViewBudget.Panel,
    loadAsset: suspend (String) -> ByteArray? = { null },
    actionRevision: Long = 0,
    accepted: Set<String> = emptySet(),
    rejected: Set<String> = emptySet(),
) {
    val session = remember { ViewSession(onAction) }
    val coroutine = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { session.pendingChanges.values.forEach { it.cancel() } } }
    session.template = view
    session.remote = data
    LaunchedEffect(data, actionRevision) {
        if (session.lastActionRevision != actionRevision) {
            session.lastActionRevision = actionRevision
            session.clean = session.clean + accepted - rejected
            session.errors = session.errors + rejected - accepted
        }
        session.drafts =
            session.drafts.filterNot { (binding, value) ->
                binding in session.clean &&
                    (nativePluginLookup(data, binding) == value ||
                        nativePluginLookup(data, binding) != session.pendingRemote[binding])
            }
    }
    val model = session.data(data)
    // The template is measured once per identity; edits only resolve the bounded template.
    val tree by
        produceState<JsonElement?>(JsonNull, view, model, budget) {
            value =
                withContext(Dispatchers.Default) {
                    boundPluginTemplate(view, budget)?.let {
                        boundPluginTemplate(nativePluginResolve(it, model), budget)
                    }
                }
        }
    if (tree == JsonNull) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    if (tree == null) {
        Text(stringResource(R.string.native_plugin_invalid))
        return
    }
    fun dispatch(
        raw: JsonElement?,
        extra: JsonObject = JsonObject(emptyMap()),
        gesture: Boolean = true,
    ) {
        if (!enabled) return
        val action = raw as? JsonObject
        val id = (raw as? JsonPrimitive)?.contentOrNull ?: action?.viewString("id") ?: return
        if (!nativePluginId(id)) return
        val args = JsonObject((action?.get("args") as? JsonObject).orEmpty() + extra)
        if (args.toString().toByteArray().size <= 1024) {
            val bindings =
                (extra["values"] as? JsonObject)?.keys.orEmpty() +
                    listOfNotNull(extra.viewString("bind"))
            bindings.forEach {
                session.pendingChanges.remove(it)?.cancel()
                session.pendingRemote[it] = nativePluginLookup(session.remote, it)
            }
            session.onAction(NativePluginViewAction(id, args, gesture))
        }
    }
    PluginNode(
        tree as? JsonObject,
        "view",
        enabled,
        session,
        model,
        coroutine,
        ::dispatch,
        loadAsset,
    )
}

private fun boundPluginTemplate(view: JsonElement, budget: NativePluginViewBudget): JsonElement? {
    if (view.toString().toByteArray().size > budget.bytes) return null
    var nodes = 0
    fun walk(value: JsonElement, depth: Int, nodeDepth: Int): JsonElement {
        if (depth > 64) return JsonNull
        if (value is JsonObject) {
            val level = nodeDepth + if (value["type"] != null) 1 else 0
            if (value["type"] != null && (++nodes > budget.nodes || level > budget.depth))
                return JsonNull
            return JsonObject(value.mapValues { (_, child) -> walk(child, depth + 1, level) })
        }
        return if (value is JsonArray)
            JsonArray(value.take(50).map { walk(it, depth + 1, nodeDepth) })
        else value
    }
    return walk(view, 0, 0).let {
        if (it is JsonArray)
            buildJsonObject {
                put("type", "stack")
                put("children", it)
            }
        else it
    }
}

@Composable
private fun PluginNode(
    raw: JsonObject?,
    path: String,
    enabled: Boolean,
    session: ViewSession,
    data: JsonElement,
    coroutine: CoroutineScope,
    dispatch: (JsonElement?, JsonObject, Boolean) -> Unit,
    loadAsset: suspend (String) -> ByteArray?,
) {
    if (raw == null) return
    val style = raw["style"] as? JsonObject
    val layout = raw.viewString("type") in setOf("stack", "row")
    val tint = style?.viewString("background")?.let(::pluginColor)
    val card = style?.viewString("card")
    if (layout && (tint != null || card in setOf("surface", "tinted"))) {
        val base = MaterialTheme.colorScheme.surfaceContainerLow
        val color =
            if (tint != null) blend(base, tint, if (isSystemInDarkTheme()) 0.16f else 0.10f)
            else if (card == "tinted") MaterialTheme.colorScheme.primaryContainer else base
        Surface(
            color = color,
            contentColor = readable(MaterialTheme.colorScheme.onSurface, color),
            shape = MaterialTheme.shapes.large,
        ) {
            Box(
                Modifier.padding(
                    (style?.number("padding", 12.0) ?: 12.0).toInt().coerceIn(0, 32).dp,
                ),
            ) {
                PluginNodeContent(raw, path, enabled, session, data, coroutine, dispatch, loadAsset)
            }
        }
    } else PluginNodeContent(raw, path, enabled, session, data, coroutine, dispatch, loadAsset)
}

@Composable
private fun PluginNodeContent(
    raw: JsonObject,
    path: String,
    enabled: Boolean,
    session: ViewSession,
    data: JsonElement,
    coroutine: CoroutineScope,
    dispatch: (JsonElement?, JsonObject, Boolean) -> Unit,
    loadAsset: suspend (String) -> ByteArray?,
) {
    val type = raw.viewString("type") ?: return
    val codeFont = LocalAppearanceCodeFont.current
    val active = enabled && raw["disabled"] != JsonPrimitive(true)
    val tone = raw.viewString("tone")
    val color =
        when (tone) {
            "bad",
            "error",
            "danger" -> MaterialTheme.colorScheme.error
            "good",
            "success" -> MaterialTheme.colorScheme.primary
            "muted" -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    val children = (raw["children"] as? JsonArray).orEmpty()
    @Composable
    fun child(value: JsonElement, index: Int) =
        key(path, index) {
            PluginNode(
                value as? JsonObject,
                "$path.$index",
                active,
                session,
                data,
                coroutine,
                dispatch,
                loadAsset,
            )
        }
    @Composable
    fun items() {
        children.forEachIndexed { index, value -> child(value, index) }
    }
    fun tap(action: JsonElement?, extra: JsonObject = JsonObject(emptyMap())) =
        dispatch(action, extra, true)
    val style = raw["style"] as? JsonObject
    val gap =
        ((style?.get("gap") ?: raw["gap"]) as? JsonPrimitive)?.intOrNull?.coerceIn(0, 32)?.dp
            ?: 8.dp
    val wrap =
        when (raw.viewString("density")) {
            "compact" -> 4.dp
            "roomy" -> 16.dp
            else -> gap
        }
    when (type) {
        "stack",
        "form" ->
            Column(verticalArrangement = Arrangement.spacedBy(wrap)) {
                items()
                if (type == "form")
                    Button(
                        enabled = active,
                        onClick = {
                            val inputs = collectInputs(raw)
                            val invalid =
                                inputs
                                    .filter { !validInput(it, session.data(data)) }
                                    .mapNotNull { it.viewString("bind") }
                                    .toSet()
                            session.errors = invalid
                            if (invalid.isEmpty())
                                tap(
                                    raw["submit"],
                                    buildJsonObject {
                                        putJsonObject("values") {
                                            inputs.forEach { input ->
                                                input.viewString("bind")?.let {
                                                    put(
                                                        it,
                                                        nativePluginLookup(session.data(data), it),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                )
                        },
                    ) {
                        Text(raw.text("submitLabel", stringResource(R.string.native_plugin_submit)))
                    }
            }
        "row" ->
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(wrap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items()
            }
        "grid" ->
            Column(verticalArrangement = Arrangement.spacedBy(wrap)) {
                val columns = raw.number("columns", 2.0).toInt().coerceIn(1, 4)
                children.chunked(columns).forEachIndexed { row, values ->
                    Row(horizontalArrangement = Arrangement.spacedBy(wrap)) {
                        values.forEachIndexed { column, value ->
                            Box(Modifier.weight(1f)) { child(value, row * columns + column) }
                        }
                    }
                }
            }
        "text" -> {
            var textStyle =
                when (style?.viewString("size") ?: raw.viewString("size")) {
                    "heading",
                    "title" -> MaterialTheme.typography.titleLarge
                    "subtitle" -> MaterialTheme.typography.titleMedium
                    "display" -> MaterialTheme.typography.headlineMedium
                    "caption" -> MaterialTheme.typography.labelSmall
                    else -> MaterialTheme.typography.bodyMedium
                }
            if (style?.viewString("size") == "mono")
                textStyle = textStyle.copy(fontFamily = codeFont)
            textStyle =
                textStyle.copy(
                    fontWeight = pluginWeight(style?.viewString("weight")),
                    fontStyle =
                        if (style?.viewBool("italic") == true) FontStyle.Italic
                        else FontStyle.Normal,
                    textDecoration =
                        when {
                            style?.viewBool("strike") == true -> TextDecoration.LineThrough
                            style?.viewBool("underline") == true -> TextDecoration.Underline
                            else -> TextDecoration.None
                        },
                )
            val surface = MaterialTheme.colorScheme.surface
            val chosen =
                style?.viewString("color")?.let(::pluginColor)?.let { readable(it, surface) }
                    ?: color
            val annotated = buildAnnotatedString {
                val spans = raw.viewObjects("spans").take(32)
                if (spans.isEmpty()) append(raw.text("text").take(2000))
                else
                    spans.forEach { span ->
                        val start = length
                        append(span.text("text").take((2000 - length).coerceAtLeast(0)))
                        addStyle(
                            SpanStyle(
                                color =
                                    span.viewString("color")?.let(::pluginColor)?.let {
                                        readable(it, surface)
                                    } ?: chosen,
                                fontWeight = pluginWeight(span.viewString("weight")),
                                fontFamily = if (span.viewBool("mono")) codeFont else null,
                            ),
                            start,
                            length,
                        )
                    }
            }
            Text(
                annotated,
                color = chosen,
                style = textStyle,
                maxLines = (style?.number("maxLines", 20.0) ?: 20.0).toInt().coerceIn(1, 20),
                textAlign =
                    when (style?.viewString("align")) {
                        "center" -> TextAlign.Center
                        "end",
                        "right" -> TextAlign.End
                        else -> TextAlign.Start
                    },
            )
        }
        "badge",
        "tag" ->
            SuggestionChip(
                onClick = {},
                enabled = false,
                label = { Text(raw.text("text", raw.text("label")), color = color) },
            )
        "divider" -> HorizontalDivider()
        "spacer" -> Spacer(Modifier.height(raw.number("size", 8.0).toInt().coerceIn(0, 64).dp))
        "codeBlock" ->
            Text(
                raw.text("code").take(8000),
                fontFamily = codeFont,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        "emptyState" ->
            Column {
                Text(raw.text("title"), style = MaterialTheme.typography.titleMedium)
                Text(raw.text("description"))
                (raw["action"] as? JsonObject)?.let { action ->
                    TextButton(onClick = { tap(action["action"]) }, enabled = active) {
                        Text(action.text("label"))
                    }
                }
            }
        "timeline" ->
            Column(verticalArrangement = Arrangement.spacedBy(wrap)) {
                raw.viewObjects("items").take(24).forEachIndexed { index, item ->
                    Text(item.text("label"), style = MaterialTheme.typography.titleSmall)
                    (item["children"] as? JsonArray).orEmpty().forEachIndexed { childIndex, value ->
                        child(value, index * 100 + childIndex)
                    }
                }
            }
        "list",
        "keyValue" ->
            Column(verticalArrangement = Arrangement.spacedBy(wrap)) {
                raw.viewObjects("items").take(50).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = active && item["action"] != null) {
                                tap(item["action"])
                            }
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.text(if (type == "keyValue") "label" else "title"),
                                color = color,
                            )
                            item.viewString("subtitle")?.let {
                                Text(it.take(200), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(item.text(if (type == "keyValue") "value" else "trailing"))
                    }
                }
            }
        "table" ->
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                val columns = (raw["columns"] as? JsonArray).orEmpty().take(8)
                Row {
                    columns.forEach {
                        Text(
                            (it as? JsonObject)?.text("label") ?: scalar(it),
                            Modifier.width(120.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                (raw["rows"] as? JsonArray).orEmpty().take(50).forEach { row ->
                    Row {
                        (row as? JsonArray).orEmpty().take(8).forEach {
                            Text(scalar(it).take(200), Modifier.width(120.dp))
                        }
                    }
                }
            }
        "actions" ->
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                raw.viewObjects("items").take(4).forEach { item ->
                    Button(onClick = { tap(item["action"]) }, enabled = active) {
                        Text(item.text("label"))
                    }
                }
            }
        "options" ->
            Column {
                val selected = remember(path, raw) { mutableStateListOf<Int>() }
                val options =
                    (raw["items"] as? JsonArray ?: raw["options"] as? JsonArray).orEmpty().take(8)
                var free by remember(path) { mutableStateOf("") }
                options.forEachIndexed { index, item ->
                    val obj = item as? JsonObject
                    FilterChip(
                        selected = index in selected,
                        onClick = {
                            if (raw.viewBool("multi")) {
                                if (index in selected) selected.remove(index)
                                else selected.add(index)
                            } else {
                                selected.clear()
                                selected.add(index)
                                tap(
                                    obj?.get("action") ?: raw["action"],
                                    buildJsonObject {
                                        put("selected", index)
                                        put("value", obj?.get("value") ?: JsonPrimitive(index))
                                    },
                                )
                            }
                        },
                        enabled = active,
                        label = { Text(obj?.text("label") ?: scalar(item)) },
                    )
                }
                if (raw["freetext"] != null && raw["freetext"] != JsonPrimitive(false))
                    OutlinedTextField(free, { free = it.take(2000) }, enabled = active)
                if (raw.viewBool("multi") || free.isNotEmpty())
                    Button(
                        onClick = {
                            tap(
                                raw["action"],
                                buildJsonObject {
                                    put("selected", JsonArray(selected.map(::JsonPrimitive)))
                                    if (free.isNotEmpty()) put("freetext", free)
                                },
                            )
                        },
                        enabled = active && (selected.isNotEmpty() || free.isNotBlank()),
                    ) {
                        Text(stringResource(R.string.native_plugin_submit))
                    }
            }
        "confirm" ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { tap(raw["action"], buildJsonObject { put("confirmed", true) }) },
                    enabled = active,
                ) {
                    Text(raw.text("confirmLabel", stringResource(R.string.native_plugin_confirm)))
                }
                OutlinedButton(
                    onClick = { tap(raw["action"], buildJsonObject { put("confirmed", false) }) },
                    enabled = active,
                ) {
                    Text(raw.text("cancelLabel", stringResource(R.string.native_plugin_cancel)))
                }
            }
        "tabs",
        "accordion",
        "collapsible",
        "carousel" -> {
            val sections = raw.viewObjects("sections").ifEmpty { raw.viewObjects("items") }.take(8)
            var selected by remember(path) { mutableIntStateOf(0) }
            if (sections.isEmpty()) {
                var expanded by remember(path) { mutableStateOf(raw.viewBool("expanded")) }
                Column {
                    TextButton(onClick = { expanded = !expanded }) { Text(raw.text("label")) }
                    PerformanceAnimatedVisibility(expanded) { Column { items() } }
                }
            } else
                Column {
                    if (type in setOf("tabs", "carousel")) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            sections.forEachIndexed { index, section ->
                                FilterChip(
                                    selected == index,
                                    { selected = index },
                                    label = { Text(section.text("label", section.text("title"))) },
                                )
                            }
                        }
                        sections.getOrNull(selected)?.let { section ->
                            (section["children"] as? JsonArray).orEmpty().forEachIndexed {
                                index,
                                value ->
                                child(value, selected * 100 + index)
                            }
                        }
                    } else
                        sections.forEachIndexed { index, section ->
                            var expanded by
                                remember(path, index) {
                                    mutableStateOf(section.viewBool("expanded"))
                                }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(section.text("label", section.text("title")))
                            }
                            PerformanceAnimatedVisibility(expanded) {
                                Column {
                                    (section["children"] as? JsonArray).orEmpty().forEachIndexed {
                                        childIndex,
                                        value ->
                                        child(value, index * 100 + childIndex)
                                    }
                                }
                            }
                        }
                }
        }
        "image",
        "avatar" -> PluginImage(raw, loadAsset)
        "gallery" ->
            Column {
                raw.viewObjects("items").take(24).forEach { item ->
                    Box(
                        Modifier.clickable(enabled = active && item["action"] != null) {
                            tap(item["action"])
                        },
                    ) {
                        PluginImage(item, loadAsset)
                    }
                }
            }
        "progress" ->
            Column {
                val value =
                    (raw.number("value", 0.0) / raw.number("max", 100.0).coerceAtLeast(0.0001))
                        .toFloat()
                        .coerceIn(0f, 1f)
                Text(raw.text("label"))
                if (raw.viewString("shape") == "ring")
                    CircularProgressIndicator(progress = { value })
                else
                    LinearProgressIndicator(
                        progress = { value },
                        modifier = Modifier.fillMaxWidth(),
                    )
                raw.viewString("caption")?.let { Text(it.take(200)) }
            }
        in inputTypes -> PluginInput(raw, path, active, session, data, coroutine, dispatch)
        else -> {
            // Base metric/chart/compare/steps/callout render through the existing dsh-ui
            // implementation.
            val parse = remember(raw) { DynamicUiParser.parse(raw.toString()) }
            if (parse.view != null) DynamicUiCard(raw.toString(), true, parse)
            else
                Text(
                    stringResource(R.string.native_plugin_invalid),
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
}
