package dev.fughetta.dshcompose.ui.uiconfig

import dev.fughetta.dshcompose.R

// A strict JSON reader for ui.json. kotlinx.serialization would do, except that it gives no line
// and column for a mistake, silently keeps the last of two equal keys, and cannot tell `1` from
// `1.0`. The skill's `check.mjs` (app/src/main/assets/skills/dsh-compose-ui/) has a line-for-line
// twin of
// this
// reader, so both report the same position and words for the same file (UiConfigParserTest runs
// the shared fixtures through each).

/** A file that is not strict JSON: where (1-based, UTF-16 columns) and why. */
class UiJsonSyntaxError(val line: Int, val column: Int, val reason: UiMessage) : Exception() {
    val text: UiMessage
        get() = UiMessage.Syntax(line, column, reason)
}

/**
 * What [readUiJson] returns: the value (objects as insertion-ordered maps, integer literals of up
 * to nine digits as [Int], every other number as [Double]) and the JSON Pointers of keys that
 * appeared twice in one object (the later value wins, in the first one's place).
 */
class UiJsonDocument(val value: Any?, val duplicateKeys: List<String>)

/** RFC 6901 escaping of one reference token. */
fun escapePointerToken(token: String) = token.replace("~", "~0").replace("/", "~1")

/**
 * Reads [text] as strict JSON (no comments, no trailing commas, double quotes only; a leading BOM
 * is skipped). Throws [UiJsonSyntaxError].
 */
fun readUiJson(text: String, maxDepth: Int = 32): UiJsonDocument {
    val reader = UiJsonParser(text, maxDepth)
    val value = reader.document()
    return UiJsonDocument(value, reader.duplicates.toList())
}

private class UiJsonParser(private val s: String, private val maxDepth: Int) {
    var i = 0
    val duplicates = mutableListOf<String>()

    private fun fail(at: Int, reason: Int, vararg args: Any): Nothing {
        var line = 1
        var lineStart = 0
        var k = 0
        while (k < at && k < s.length) {
            if (s[k] == '\n') {
                line++
                lineStart = k + 1
            }
            k++
        }
        throw UiJsonSyntaxError(line, at - lineStart + 1, UiMessage.Text(reason, args.toList()))
    }

    private val end
        get() = i >= s.length

    private val c
        get() = s[i]

    private fun ws() {
        while (!end && (c == ' ' || c == '\t' || c == '\n' || c == '\r')) i++
    }

    private fun digit(c: Char) = c in '0'..'9'

    private fun startsValue(c: Char) =
        c == '"' || c == '{' || c == '[' || c == '-' || digit(c) || c == 't' || c == 'f' || c == 'n'

    private fun identStart(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c == '$'

    fun document(): Any? {
        if (!end && c == '﻿') i++
        ws()
        if (end) fail(i, R.string.ui_json_empty_file)
        val value = value("", 0)
        ws()
        if (!end) fail(i, R.string.ui_json_extra_content)
        return value
    }

    private fun value(pointer: String, depth: Int): Any? {
        ws()
        if (end) fail(i, R.string.ui_json_incomplete_file)
        val c = c
        if (c == '{') return obj(pointer, depth + 1)
        if (c == '[') return array(pointer, depth + 1)
        if (c == '"') return string()
        if (c == '-' || digit(c)) return number()
        if (s.startsWith("true", i)) {
            i += 4
            return true
        }
        if (s.startsWith("false", i)) {
            i += 5
            return false
        }
        if (s.startsWith("null", i)) {
            i += 4
            return null
        }
        if (c == '/') fail(i, R.string.ui_json_comments_unsupported)
        if (c == '\'') fail(i, R.string.ui_json_double_quotes)
        if (c == '}' || c == ']' || c == ',') fail(i, R.string.ui_json_missing_value)
        fail(i, R.string.ui_json_unknown_content, c.toString())
    }

    private fun obj(pointer: String, depth: Int): Map<String, Any?> {
        if (depth > maxDepth) fail(i, R.string.ui_json_too_deep)
        i++
        val map = LinkedHashMap<String, Any?>()
        ws()
        if (!end && c == '}') {
            i++
            return map
        }
        var comma = -1
        while (true) {
            ws()
            if (end) fail(i, R.string.ui_json_incomplete_file)
            val c = c
            if (c != '"') {
                if (c == '}' && comma >= 0) fail(comma, R.string.ui_json_extra_comma)
                if (c == '/') fail(i, R.string.ui_json_comments_unsupported)
                if (c == '\'') fail(i, R.string.ui_json_double_quotes)
                if (identStart(c)) fail(i, R.string.ui_json_quoted_key)
                fail(i, R.string.ui_json_expected_key)
            }
            val key = string()
            ws()
            if (end) fail(i, R.string.ui_json_incomplete_file)
            if (this.c != ':') fail(i, R.string.ui_json_missing_colon)
            i++
            val child = "$pointer/${escapePointerToken(key)}"
            val value = value(child, depth)
            if (map.containsKey(key)) duplicates += child
            map[key] = value
            ws()
            if (end) fail(i, R.string.ui_json_incomplete_file)
            val next = this.c
            if (next == ',') {
                comma = i
                i++
                continue
            }
            if (next == '}') {
                i++
                return map
            }
            if (next == '/') fail(i, R.string.ui_json_comments_unsupported)
            if (startsValue(next)) fail(i, R.string.ui_json_missing_comma)
            fail(i, R.string.ui_json_unexpected_content, next.toString())
        }
    }

    private fun array(pointer: String, depth: Int): List<Any?> {
        if (depth > maxDepth) fail(i, R.string.ui_json_too_deep)
        i++
        val list = mutableListOf<Any?>()
        ws()
        if (!end && c == ']') {
            i++
            return list
        }
        var comma = -1
        while (true) {
            ws()
            if (!end && c == ']' && comma >= 0) fail(comma, R.string.ui_json_extra_comma)
            list += value("$pointer/${list.size}", depth)
            ws()
            if (end) fail(i, R.string.ui_json_incomplete_file)
            val next = c
            if (next == ',') {
                comma = i
                i++
                continue
            }
            if (next == ']') {
                i++
                return list
            }
            if (next == '/') fail(i, R.string.ui_json_comments_unsupported)
            if (startsValue(next)) fail(i, R.string.ui_json_missing_comma)
            fail(i, R.string.ui_json_unexpected_content, next.toString())
        }
    }

    private fun string(): String {
        val start = i
        i++
        val out = StringBuilder()
        while (true) {
            if (end) fail(start, R.string.ui_json_unterminated_string)
            val c = c
            if (c == '"') {
                i++
                return out.toString()
            }
            if (c == '\\') {
                if (i + 1 >= s.length) fail(start, R.string.ui_json_unterminated_string)
                when (val e = s[i + 1]) {
                    '"',
                    '\\',
                    '/' -> out.append(e)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        val hex = if (i + 6 > s.length) "" else s.substring(i + 2, i + 6)
                        if (!HEX4.matches(hex)) fail(i, R.string.ui_json_bad_escape)
                        out.append(hex.toInt(16).toChar())
                        i += 4
                    }
                    else -> fail(i, R.string.ui_json_bad_escape)
                }
                i += 2
                continue
            }
            if (c == '\n' || c == '\r') fail(i, R.string.ui_json_string_newline)
            if (c < ' ') fail(i, R.string.ui_json_control_character)
            out.append(c)
            i++
        }
    }

    private fun number(): Any {
        val start = i
        if (c == '-') i++
        if (end) fail(i, R.string.ui_json_bad_number)
        if (c == '0') i++
        else if (digit(c)) while (!end && digit(c)) i++ else fail(i, R.string.ui_json_bad_number)
        var integer = true
        if (!end && c == '.') {
            integer = false
            i++
            if (end || !digit(c)) fail(i, R.string.ui_json_bad_number)
            while (!end && digit(c)) i++
        }
        if (!end && (c == 'e' || c == 'E')) {
            integer = false
            i++
            if (!end && (c == '+' || c == '-')) i++
            if (end || !digit(c)) fail(i, R.string.ui_json_bad_number)
            while (!end && digit(c)) i++
        }
        val literal = s.substring(start, i)
        val digits = if (literal.startsWith('-')) literal.length - 1 else literal.length
        return if (integer && digits <= 9) literal.toInt() else literal.toDouble()
    }

    companion object {
        val HEX4 = Regex("[0-9A-Fa-f]{4}")
    }
}
