package dev.fughetta.dshcompose.ui.dynamicui

import kotlinx.collections.immutable.*
import kotlinx.serialization.json.*

/** Node and depth budget shared by the reader and the mapper; also collects truncation issues. */
internal class GenuiBudget {
    var count = 1
    val issues = mutableListOf<UiIssue>()
    val pretty = Json { prettyPrint = true }

    fun note(path: String, kind: UiIssueKind) {
        if (issues.size < 16) issues.add(UiIssue(path, kind))
    }

    fun <T> limit(values: List<T>, maximum: Int, path: String): List<T> {
        if (values.size > maximum) note(path, UiIssueKind.Truncated)
        return values.take(maximum)
    }

    fun clipped(value: String, maximum: Int, path: String): String {
        if (value.length > maximum) note(path, UiIssueKind.Truncated)
        return clip(value, maximum)
    }

    fun allocate(path: String, depth: Int): Boolean {
        if (depth > 8) {
            note(path, UiIssueKind.Depth)
            return false
        }
        if (count >= 120) {
            note(path, UiIssueKind.Truncated)
            return false
        }
        count++
        return true
    }
}

private val highlight = Regex("""==([^=\n]+)==""")

internal fun genuiRawString(value: JsonElement?) =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun genuiStr(value: JsonElement?, trim: Boolean = true): String? =
    (value as? JsonPrimitive)
        ?.takeUnless { it is JsonNull || !it.isString && it.booleanOrNull != null }
        ?.content
        ?.let { if (trim) it.trim() else it }
        ?.takeIf { it.isNotEmpty() }

/** Action names and submission keys are one plain line, as the web card sends them. */
internal fun genuiAction(raw: JsonObject, name: String = "action"): String? = genuiKey(raw[name])

internal fun genuiKey(value: JsonElement?): String? =
    genuiStr(value)?.takeIf { it.length <= 200 && it.none { c -> c == '\n' || c == '\r' } }

internal fun genuiNumber(value: JsonElement?): Double? =
    genuiStr(value)?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun genuiTone(value: JsonElement?): UiTone =
    when (genuiStr(value)) {
        "success",
        "good",
        "positive" -> UiTone.Good
        "warn",
        "warning" -> UiTone.Warn
        "danger",
        "error",
        "bad" -> UiTone.Bad
        "muted" -> UiTone.Muted
        else -> UiTone.Info
    }

internal fun genuiCell(value: JsonElement?): String =
    when (value) {
        null,
        JsonNull -> ""
        is JsonPrimitive ->
            if (!value.isString && value.booleanOrNull != null) {
                if (value.boolean) "✓" else ""
            } else value.content
        else -> "—"
    }

internal fun genuiHighlight(value: String): String = value.replace(highlight) { it.groupValues[1] }
