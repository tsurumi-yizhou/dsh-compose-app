package dev.fughetta.dshcompose.transcript

import android.util.Xml
import dev.fughetta.dshcompose.ui.transcript.TranscriptSheet
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.collections.immutable.*
import org.xmlpull.v1.XmlPullParser

internal object TranscriptTables {
    fun delimited(bytes: ByteArray, tsv: Boolean, partial: Boolean): TranscriptSheet {
        val text =
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF")
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    bytes.toString(Charsets.UTF_16BE).removePrefix("\uFEFF")
                else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            }
        val delimiter = if (tsv) '\t' else ','
        val rows = mutableListOf<PersistentList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var started = false
        var i = 0
        var truncated = partial
        fun endField() {
            if (row.size < 200) row += field.toString() else truncated = true
            field.setLength(0)
            started = false
        }
        fun endRow() {
            endField()
            rows += row.toPersistentList()
            row = mutableListOf()
        }
        while (i < text.length && rows.size < 5000) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (text.getOrNull(i + 1) == '"') {
                        field.append('"')
                        i += 2
                        continue
                    }
                    quoted = false
                } else field.append(ch)
                i++
                continue
            }
            when (ch) {
                '"' ->
                    if (!started) {
                        quoted = true
                        started = true
                    } else field.append(ch)
                delimiter -> endField()
                '\n',
                '\r' -> {
                    if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                else -> {
                    field.append(ch)
                    started = true
                }
            }
            i++
        }
        if (i < text.length) truncated = true
        if (quoted && !partial) error("artifact/invalid-table")
        if (
            !quoted &&
                !partial &&
                rows.size < 5000 &&
                (row.isNotEmpty() || field.isNotEmpty() || started)
        )
            endRow()
        return TranscriptSheet(
            "",
            rows.toPersistentList(),
            rows.maxOfOrNull { it.size } ?: 0,
            truncated,
        )
    }

    private data class Relation(val target: String, val type: String)

    fun xlsx(file: File): PersistentList<TranscriptSheet> =
        ZipFile(file).use { zip ->
            var expanded = 0L
            fun parse(path: String, visit: (XmlPullParser) -> Unit) {
                val entry = zip.getEntry(path) ?: return
                zip.getInputStream(entry).use { raw ->
                    val input =
                        object : java.io.FilterInputStream(raw) {
                            override fun read(): Int {
                                val value = super.read()
                                if (value >= 0 && ++expanded > 64L * 1024 * 1024)
                                    error("artifact/workbook-too-large")
                                return value
                            }

                            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                                val count = super.read(bytes, off, len)
                                if (count > 0) {
                                    expanded += count
                                    if (expanded > 64L * 1024 * 1024)
                                        error("artifact/workbook-too-large")
                                }
                                return count
                            }
                        }
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                    parser.setInput(input, "UTF-8")
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        visit(parser)
                        parser.next()
                    }
                }
            }
            fun attr(p: XmlPullParser, key: String): String? {
                for (i in 0 until p.attributeCount) if (p.getAttributeName(i) == key)
                    return p.getAttributeValue(i)
                return null
            }
            fun relations(path: String, base: String): Map<String, Relation> {
                val out = mutableMapOf<String, Relation>()
                parse(path) { p ->
                    if (
                        p.eventType == XmlPullParser.START_TAG &&
                            p.name == "Relationship" &&
                            attr(p, "TargetMode") != "External"
                    ) {
                        val id = attr(p, "Id")
                        val target = attr(p, "Target")
                        if (id != null && target != null) {
                            val name =
                                if (target.startsWith('/')) target.removePrefix("/")
                                else Paths.get(base, target).normalize().toString()
                            out[id] = Relation(name, attr(p, "Type").orEmpty())
                        }
                    }
                }
                return out
            }
            val root = relations("_rels/.rels", "")
            val workbook =
                root.values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
                    ?: "xl/workbook.xml"
            val base = workbook.substringBeforeLast('/', "")
            val rels = relations("$base/_rels/${workbook.substringAfterLast('/')}.rels", base)
            val sheetRefs = mutableListOf<Pair<String, String>>()
            var date1904 = false
            parse(workbook) { p ->
                if (p.eventType == XmlPullParser.START_TAG) {
                    if (p.name == "workbookPr") date1904 = attr(p, "date1904") in setOf("1", "true")
                    if (p.name == "sheet" && attr(p, "state") !in setOf("hidden", "veryHidden")) {
                        val id = attr(p, "id")
                        val relation = rels[id]
                        if (relation != null)
                            sheetRefs += (attr(p, "name").orEmpty() to relation.target)
                    }
                }
            }
            val shared = mutableListOf<String>()
            var text = StringBuilder()
            var inSi = false
            var inT = false
            parse(
                rels.values.firstOrNull { it.type.endsWith("/sharedStrings") }?.target
                    ?: "$base/sharedStrings.xml",
            ) { p ->
                when (p.eventType) {
                    XmlPullParser.START_TAG ->
                        when (p.name) {
                            "si" -> {
                                inSi = true
                                text = StringBuilder()
                            }
                            "t" -> inT = true
                        }
                    XmlPullParser.TEXT -> if (inSi && inT) text.append(p.text)
                    XmlPullParser.END_TAG ->
                        when (p.name) {
                            "t" -> inT = false
                            "si" -> {
                                shared += text.toString()
                                inSi = false
                            }
                        }
                }
            }
            val custom = mutableMapOf<Int, String>()
            val formats = mutableListOf<Int>()
            var xfs = false
            parse(
                rels.values.firstOrNull { it.type.endsWith("/styles") }?.target
                    ?: "$base/styles.xml",
            ) { p ->
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "numFmt" ->
                            attr(p, "numFmtId")?.toIntOrNull()?.let {
                                custom[it] = attr(p, "formatCode").orEmpty()
                            }
                        "cellXfs" -> xfs = true
                        "xf" -> if (xfs) formats += attr(p, "numFmtId")?.toIntOrNull() ?: 0
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "cellXfs") xfs = false
            }
            var cellsLeft = 250000
            val output = mutableListOf<TranscriptSheet>()
            sheetRefs.forEach { (name, path) ->
                if (cellsLeft <= 0) {
                    output += TranscriptSheet(name, persistentListOf(), 0, true)
                    return@forEach
                }
                val sparse = sortedMapOf<Int, MutableMap<Int, String>>()
                var rowIndex = 0
                var column = 0
                var type = ""
                var style = 0
                var value = StringBuilder()
                var inline = StringBuilder()
                var capture = ""
                var truncated = false
                var insideCell = false
                var widest = 0
                parse(path) { p ->
                    when (p.eventType) {
                        XmlPullParser.START_TAG ->
                            when (p.name) {
                                "row" ->
                                    rowIndex =
                                        (attr(p, "r")?.toIntOrNull()?.minus(1) ?: rowIndex)
                                            .coerceAtLeast(0)
                                "c" -> {
                                    insideCell = true
                                    val reference =
                                        attr(p, "r").orEmpty().takeWhile { it.isLetter() }
                                    column =
                                        if (reference.isEmpty()) column
                                        else
                                            reference.fold(0) { n, c ->
                                                n * 26 + (c.uppercaseChar() - 'A' + 1)
                                            } - 1
                                    type = attr(p, "t").orEmpty()
                                    style = attr(p, "s")?.toIntOrNull() ?: 0
                                    value = StringBuilder()
                                    inline = StringBuilder()
                                }
                                "v",
                                "t" -> capture = p.name
                            }
                        XmlPullParser.TEXT ->
                            if (insideCell)
                                when (capture) {
                                    "v" -> value.append(p.text)
                                    "t" -> inline.append(p.text)
                                }
                        XmlPullParser.END_TAG ->
                            when (p.name) {
                                "v",
                                "t" -> capture = ""
                                "row" -> rowIndex++
                                "c" -> {
                                    insideCell = false
                                    if (
                                        rowIndex >= 5000 ||
                                            column >= 200 ||
                                            (rowIndex + 1L) * (maxOf(widest, column + 1)) >
                                                cellsLeft
                                    ) {
                                        truncated = true
                                    } else {
                                        widest = maxOf(widest, column + 1)
                                        val raw = value.toString()
                                        val rendered =
                                            when (type) {
                                                "s" ->
                                                    raw.toIntOrNull()
                                                        ?.let { shared.getOrNull(it) }
                                                        .orEmpty()
                                                "inlineStr" -> inline.toString()
                                                "b" -> if (raw == "1") "TRUE" else "FALSE"
                                                "str",
                                                "e",
                                                "d" -> raw
                                                else ->
                                                    formatNumber(
                                                        raw,
                                                        formats.getOrNull(style)?.let {
                                                            custom[it] ?: builtIn(it)
                                                        },
                                                        date1904,
                                                    )
                                            }
                                        sparse.getOrPut(rowIndex) { mutableMapOf() }[column] =
                                            rendered
                                    }
                                    column++
                                }
                            }
                    }
                }
                val count = (sparse.lastKeyOrNull()?.plus(1) ?: 0).coerceAtMost(5000)
                val rows =
                    (0 until count)
                        .map { r ->
                            (0 until widest)
                                .map { c -> sparse[r]?.get(c).orEmpty() }
                                .toPersistentList()
                        }
                        .toPersistentList()
                cellsLeft -= count * widest
                output += TranscriptSheet(name, rows, widest, truncated)
            }
            output.toPersistentList()
        }

    private fun <K, V> java.util.SortedMap<K, V>.lastKeyOrNull(): K? =
        if (isEmpty()) null else lastKey()

    private fun builtIn(id: Int): String? =
        when (id) {
            1 -> "0"
            2 -> "0.00"
            3 -> "#,##0"
            4 -> "#,##0.00"
            9 -> "0%"
            10 -> "0.00%"
            in 14..17 -> "yyyy-mm-dd"
            18,
            20 -> "hh:mm"
            19,
            21 -> "hh:mm:ss"
            22 -> "yyyy-mm-dd hh:mm"
            45,
            46,
            47 -> "hh:mm:ss"
            else -> null
        }

    private fun formatNumber(raw: String, format: String?, date1904: Boolean): String {
        val number = raw.toDoubleOrNull() ?: return raw
        if (!number.isFinite()) return raw
        val code =
            format?.substringBefore(';')?.replace(Regex("\"[^\"]*\"|\\[[^]]*]|\\\\."), "")
                ?: return raw
        val date = Regex("[dy]|m{3,}", RegexOption.IGNORE_CASE).containsMatchIn(code)
        val time = Regex("[hs]", RegexOption.IGNORE_CASE).containsMatchIn(code)
        if (date || time) {
            val at =
                (if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0)
                    else LocalDateTime.of(1899, 12, 30, 0, 0))
                    .plusDays(kotlin.math.floor(number).toLong())
                    .plusNanos(((number - kotlin.math.floor(number)) * 86400 * 1000000000).toLong())
            val pattern =
                if (date && time) "yyyy-MM-dd HH:mm"
                else if (date) "yyyy-MM-dd"
                else if ('s' in code.lowercase()) "HH:mm:ss" else "HH:mm"
            return at.format(DateTimeFormatter.ofPattern(pattern))
        }
        val decimals =
            code
                .substringAfter('.', "")
                .takeWhile { it == '0' || it == '#' }
                .length
                .coerceAtMost(12)
        val value = if ('%' in code) number * 100 else number
        if ('0' !in code && '#' !in code) return raw
        return String.format(Locale.ROOT, "%${if(',' in code)","else ""}.${decimals}f", value) +
            (if ('%' in code) "%" else "")
    }
}
