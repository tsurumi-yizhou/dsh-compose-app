package dev.fughetta.dshcompose.ui.nativeplugins

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.feature.chat.R
import dev.fughetta.dshcompose.ui.dynamicui.*
import dev.fughetta.dshcompose.ui.images.BytesImage
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable
internal fun PluginInput(
    raw: JsonObject,
    path: String,
    enabled: Boolean,
    session: ViewSession,
    data: JsonElement,
    scope: CoroutineScope,
    dispatch: (JsonElement?, JsonObject, Boolean) -> Unit,
) {
    if (
        raw.viewBool("password") ||
            raw.viewBool("obscureText") ||
            raw.viewString("inputType") == "password" ||
            raw.viewString("keyboardType") == "password"
    ) {
        Text(
            stringResource(R.string.native_plugin_invalid),
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val binding = raw.viewString("bind")?.takeIf(::nativePluginWritable)
    val type = raw.viewString("type").orEmpty()
    val value = binding?.let { nativePluginLookup(data, it) } ?: raw["value"] ?: JsonNull
    val label = raw.text("label")
    val active = enabled && binding != null && raw["readOnly"] != JsonPrimitive(true)
    val error = binding in session.errors
    val maxLength = raw.number("maxLength", 2000.0).toInt().coerceIn(0, 2000)
    fun edit(next: JsonElement) {
        if (!active || next.toString().toByteArray().size > 4096) return
        session.drafts = session.drafts + (binding to next)
        session.errors = session.errors - binding
        session.clean = session.clean - binding
        session.pendingChanges.remove(binding)?.cancel()
        raw["onChange"]?.let { action ->
            session.pendingChanges[binding] = scope.launch {
                delay(300)
                if (validInput(raw, session.data(session.remote))) {
                    val template =
                        (session.template as? JsonObject)?.let(::collectInputs)?.find {
                            it.viewString("bind") == binding
                        }
                    val latestAction =
                        template?.get("onChange")?.let {
                            nativePluginResolve(it, session.data(session.remote))
                        } ?: action
                    dispatch(
                        latestAction,
                        buildJsonObject {
                            put("bind", binding)
                            put("value", next)
                        },
                        false,
                    )
                }
            }
        }
    }
    Column {
        when (type) {
            "toggle" ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    Switch(
                        (value as? JsonPrimitive)?.booleanOrNull == true,
                        { edit(JsonPrimitive(it)) },
                        enabled = active,
                    )
                }
            "slider",
            "rating",
            "stepper" -> {
                val minimum = raw.number("min", 0.0).toFloat()
                val maximum =
                    raw.number("max", if (type == "rating") 5.0 else 100.0)
                        .toFloat()
                        .coerceAtLeast(minimum + 0.001f)
                val current =
                    ((value as? JsonPrimitive)?.floatOrNull ?: minimum).coerceIn(minimum, maximum)
                Text("$label: $current")
                if (type == "stepper")
                    Row {
                        val step = raw.number("step", 1.0).coerceAtLeast(0.001)
                        OutlinedButton(
                            onClick = {
                                edit(
                                    JsonPrimitive(
                                        (current - step).coerceAtLeast(minimum.toDouble()),
                                    ),
                                )
                            },
                            enabled = active && current > minimum,
                        ) {
                            Text("−")
                        }
                        OutlinedButton(
                            onClick = {
                                edit(
                                    JsonPrimitive(
                                        (current + step).coerceAtMost(maximum.toDouble()),
                                    ),
                                )
                            },
                            enabled = active && current < maximum,
                        ) {
                            Text("+")
                        }
                    }
                else if (type == "slider" && raw.viewBool("range")) {
                    val range = value as? JsonArray
                    val lower =
                        ((range?.getOrNull(0) as? JsonPrimitive)?.floatOrNull ?: minimum).coerceIn(
                            minimum,
                            maximum,
                        )
                    val upper =
                        ((range?.getOrNull(1) as? JsonPrimitive)?.floatOrNull ?: maximum).coerceIn(
                            lower,
                            maximum,
                        )
                    val slider =
                        rememberRangeSliderState(lower, upper, trackRange = minimum..maximum)
                    // Widen before narrowing so both controlled endpoints can move together.
                    slider.startValue = minOf(lower, slider.startValue)
                    slider.endValue = upper
                    slider.startValue = lower
                    RangeSlider(
                        slider,
                        onValueChange = {
                            edit(
                                JsonArray(
                                    listOf(JsonPrimitive(it.start), JsonPrimitive(it.endInclusive)),
                                ),
                            )
                        },
                        enabled = active,
                    )
                } else {
                    val steps =
                        if (type == "rating") (maximum - minimum).toInt().coerceIn(1, 10) - 1 else 0
                    val slider =
                        rememberSliderState(current, steps = steps, trackRange = minimum..maximum)
                    slider.value = current
                    Slider(
                        slider,
                        onValueChange = {
                            edit(
                                JsonPrimitive(
                                    if (type == "rating") kotlin.math.round(it).toDouble()
                                    else it.toDouble(),
                                ),
                            )
                        },
                        enabled = active,
                    )
                }
            }
            "select",
            "segmented",
            "chips" -> {
                Text(label)
                Column {
                    (raw["options"] as? JsonArray).orEmpty().take(24).forEach { option ->
                        val item = option as? JsonObject
                        val choice = item?.get("value") ?: option
                        val multi = raw.viewBool("multi")
                        val selected =
                            if (multi) choice in (value as? JsonArray).orEmpty()
                            else value == choice
                        FilterChip(
                            selected,
                            {
                                edit(
                                    if (multi)
                                        JsonArray(
                                            (value as? JsonArray).orEmpty().let {
                                                if (selected) it - choice else it + choice
                                            },
                                        )
                                    else choice,
                                )
                            },
                            enabled = active,
                            label = { Text(item?.text("label") ?: scalar(option)) },
                        )
                    }
                }
            }
            else ->
                OutlinedTextField(
                    scalar(value).takeUnless { value == JsonNull }.orEmpty(),
                    { text ->
                        val clipped = text.take(maxLength)
                        edit(
                            if (type == "number")
                                clipped
                                    .toDoubleOrNull()
                                    ?.takeIf(Double::isFinite)
                                    ?.let(::JsonPrimitive) ?: JsonPrimitive(clipped)
                            else JsonPrimitive(clipped),
                        )
                    },
                    label = { Text(label) },
                    placeholder = { Text(raw.text("placeholder")) },
                    enabled = active,
                    isError = error,
                    singleLine =
                        raw.number("maxLines", if (raw.viewBool("multiline")) 4.0 else 1.0) <= 1,
                    maxLines =
                        raw.number("maxLines", if (raw.viewBool("multiline")) 4.0 else 1.0)
                            .toInt()
                            .coerceIn(1, 8),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                if (type == "number") KeyboardType.Decimal else KeyboardType.Text,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        if (error)
            Text(
                stringResource(R.string.native_plugin_invalid_value),
                color = MaterialTheme.colorScheme.error,
            )
        raw.viewString("help")?.let {
            Text(it.take(200), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PluginImage(raw: JsonObject, loadAsset: suspend (String) -> ByteArray?) {
    val source = raw.viewString("src")
    val bytes by
        produceState<ByteArray?>(null, source) {
            value =
                try {
                    when {
                        source == null -> null
                        source.startsWith("asset:") ->
                            loadAsset(source.removePrefix("asset:"))?.takeIf { bytes ->
                                rasterSize(bytes)?.let { (w, h) ->
                                    w in 1..4096 && h in 1..4096 && w.toLong() * h <= 4_000_000
                                } == true
                            }
                        source.length <= 65536 && inlinePicture(source) != null ->
                            Base64.getDecoder().decode(source.substringAfter(','))
                        else -> null
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
        }
    Column {
        val loaded = bytes
        if (loaded != null)
            BytesImage(loaded, raw.text("alt"), Modifier.fillMaxWidth().heightIn(max = 360.dp))
        else Text(raw.text("alt", "—"))
        raw.viewString("caption")?.let {
            Text(it.take(200), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal val inputTypes =
    setOf(
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
    )

internal fun JsonObject.text(key: String, fallback: String = "") =
    get(key)?.let(::scalar)?.takeUnless { it == "—" } ?: fallback

internal fun JsonObject.number(key: String, fallback: Double): Double =
    (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) ?: fallback

internal fun scalar(value: JsonElement): String = (value as? JsonPrimitive)?.contentOrNull ?: "—"

internal fun pluginWeight(value: String?): FontWeight =
    when (value) {
        "medium" -> FontWeight.Medium
        "semibold" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        else -> FontWeight.Normal
    }

internal fun pluginColor(value: String): Color? {
    val named =
        mapOf(
            "red" to 0xD04545,
            "orange" to 0xCA6328,
            "amber" to 0xAA7900,
            "green" to 0x258051,
            "teal" to 0x167F84,
            "blue" to 0x3D6FD6,
            "indigo" to 0x5B5CC7,
            "violet" to 0x8554BB,
            "pink" to 0xB94F83,
            "gray" to 0x697382,
        )
    val rgb =
        named[value]
            ?: value
                .takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
                ?.removePrefix("#")
                ?.toIntOrNull(16)
            ?: return null
    return Color(0xFF000000L or rgb.toLong())
}

internal fun blend(from: Color, to: Color, amount: Float) =
    Color(
        from.red * (1 - amount) + to.red * amount,
        from.green * (1 - amount) + to.green * amount,
        from.blue * (1 - amount) + to.blue * amount,
    )

internal fun readable(requested: Color, background: Color): Color {
    fun ratio(color: Color): Float {
        val a = color.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
    if (ratio(requested) >= 4.5f) return requested
    val target = if (ratio(Color.Black) >= ratio(Color.White)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2
        if (ratio(blend(requested, target, middle)) >= 4.5f) high = middle else low = middle
    }
    return blend(requested, target, high)
}

internal fun collectInputs(root: JsonObject): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    fun visit(node: JsonObject) {
        if (out.size >= 32) return
        if (
            node.viewString("type") in inputTypes &&
                node.viewString("bind")?.let(::nativePluginWritable) == true &&
                !node.viewBool("readOnly")
        )
            out.add(node)
        (node["children"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().forEach(::visit)
        node.viewObjects("sections").forEach(::visit)
        node.viewObjects("items").forEach(::visit)
    }
    visit(root)
    return out
}

internal fun validInput(raw: JsonObject, data: JsonElement): Boolean {
    val binding = raw.viewString("bind") ?: return false
    val value = nativePluginLookup(data, binding)
    val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (
        raw.viewBool("required") &&
            (value == JsonNull || text.isBlank() || value is JsonArray && value.isEmpty())
    )
        return false
    if (value == JsonNull || value is JsonPrimitive && text.isEmpty()) return true
    when (raw.viewString("type")) {
        "toggle" -> if ((value as? JsonPrimitive)?.booleanOrNull == null) return false
        "number",
        "stepper",
        "slider",
        "rating" -> {
            val numbers =
                if (raw.viewBool("range"))
                    (value as? JsonArray)
                        ?.takeIf { it.size == 2 }
                        ?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return false }
                        ?: return false
                else listOf(text.toDoubleOrNull() ?: return false)
            if (
                numbers.any {
                    !it.isFinite() ||
                        it < raw.number("min", -Double.MAX_VALUE) ||
                        it > raw.number("max", Double.MAX_VALUE)
                }
            )
                return false
            if (raw.viewString("type") == "rating" && numbers.any { it % 1 != 0.0 }) return false
            if (numbers.size == 2 && numbers[0] > numbers[1]) return false
        }
        "date" -> {
            val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return false
            if (date.year !in 1900..2100) return false
            raw.viewString("min")?.let { if (text < it) return false }
            raw.viewString("max")?.let { if (text > it) return false }
        }
        "time" ->
            if (
                !text.matches(Regex("[0-9]{2}:[0-9]{2}")) ||
                    runCatching { LocalTime.parse(text) }.isFailure
            )
                return false
        "select",
        "chips",
        "segmented" -> {
            val choices =
                (raw["options"] as? JsonArray).orEmpty().map {
                    (it as? JsonObject)?.get("value") ?: it
                }
            if (
                (if (raw.viewBool("multi")) (value as? JsonArray).orEmpty() else listOf(value))
                    .any { it !in choices }
            )
                return false
        }
    }
    if (
        value is JsonPrimitive &&
            value.isString &&
            (text.length < raw.number("minLength", 0.0) ||
                text.length > raw.number("maxLength", 2000.0))
    )
        return false
    val pattern =
        when (raw.viewString("pattern")) {
            null -> return true
            "email" -> "[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+"
            "integer" -> "[+-]?[0-9]+"
            "decimal" -> "[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
            "alphanumeric" -> "[A-Za-z0-9]+"
            "slug" -> "[a-z0-9]+(?:-[a-z0-9]+)*"
            else -> return false
        }
    return text.matches(Regex(pattern))
}
