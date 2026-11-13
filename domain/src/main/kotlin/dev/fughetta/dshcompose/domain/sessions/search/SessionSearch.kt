// Portions derived from DeepSeek Harness, MIT, Copyright (c) 2026 DeepSeek.
package dev.fughetta.dshcompose.domain.sessions.search

import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import java.util.Locale
import kotlinx.collections.immutable.*

data class SessionContentHit(val sessionId: String, val snippet: String)

data class SessionSearchPage(val items: PersistentList<SessionContentHit>, val hasMore: Boolean)

/** Folding preserves UTF-16 offsets, including supplementary letters and full-width IME input. */
fun foldSessionSearch(text: String): String {
    val chars = text.toCharArray()
    var i = 0
    while (i < chars.size) {
        val c = chars[i]
        when {
            c in '\uFF01'..'\uFF5E' -> chars[i] = (c.code - 0xFEE0).toChar().lowercaseChar()
            c == '\u3000' -> chars[i] = ' '
            c.isHighSurrogate() && i + 1 < chars.size && chars[i + 1].isLowSurrogate() -> {
                val lowered = text.substring(i, i + 2).lowercase(Locale.ROOT)
                if (lowered.length == 2) {
                    chars[i] = lowered[0]
                    chars[i + 1] = lowered[1]
                }
                i++
            }
            !c.isSurrogate() ->
                c.toString()
                    .lowercase(Locale.ROOT)
                    .takeIf { it.length == 1 }
                    ?.let { chars[i] = it[0] }
        }
        i++
    }
    return String(chars)
}

private val searchWhitespace = unicodeRegex("\\s+")

fun sessionSearchTerms(query: String): List<String> =
    foldSessionSearch(query).split(searchWhitespace).filter(String::isNotEmpty).distinct()

fun sessionSearchKey(query: String): String =
    query
        .lowercase(Locale.ROOT)
        .split(searchWhitespace)
        .filter(String::isNotEmpty)
        .joinToString(" ")

fun sessionFolder(cwd: String?): String? = cwd?.let {
    val path = it.trimEnd('/', '\\')
    if (path.isEmpty()) if (it.isEmpty()) null else "/"
    else path.substringAfterLast('/').substringAfterLast('\\')
}

fun limitSessionSearchInput(text: String): String =
    if (text.length <= 200) text
    else text.take(if (text[199].isHighSurrogate() && text[200].isLowSurrogate()) 199 else 200)

data class SearchRange(val start: Int, val end: Int)

fun sessionSearchRanges(text: String, terms: List<String>): List<SearchRange> {
    val folded = foldSessionSearch(text)
    val ranges = ArrayList<SearchRange>()
    for (term in terms.filter(String::isNotEmpty)) {
        var at = folded.indexOf(term)
        while (at >= 0) {
            ranges.add(SearchRange(at, at + term.length))
            at = folded.indexOf(term, at + term.length)
        }
    }
    ranges.sortWith(compareBy<SearchRange> { it.start }.thenByDescending { it.end })
    val merged = ArrayList<SearchRange>()
    for (range in ranges) {
        val last = merged.lastOrNull()
        if (last != null && range.start <= last.end)
            merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
        else merged.add(range)
    }
    return merged
}

data class SearchExcerpt(val text: String, val ranges: List<SearchRange>)

fun sessionSearchExcerpt(text: String, terms: List<String>): SearchExcerpt {
    val ranges = sessionSearchRanges(text, terms)
    val first = ranges.firstOrNull()?.start ?: return SearchExcerpt(text, ranges)
    if (first <= 15) return SearchExcerpt(text, ranges)
    var cut = first - 14
    if (text[cut].isLowSurrogate()) cut--
    val space = text.indexOf(' ', cut)
    if (space >= 0 && space - cut <= 8 && space < first - 1) cut = space + 1
    return SearchExcerpt(
        "…" + text.substring(cut),
        ranges.map { SearchRange(it.start - cut + 1, it.end - cut + 1) },
    )
}

data class SessionSearchHit(
    val session: SessionSummary,
    val archived: Boolean,
    val byTitle: Boolean,
    val snippet: String?,
)

data class SessionSearchResults(
    val terms: List<String> = emptyList(),
    val active: PersistentList<SessionSearchHit> = persistentListOf(),
    val archived: PersistentList<SessionSearchHit> = persistentListOf(),
    val showFolders: Boolean = false,
) {
    val isEmpty
        get() = active.isEmpty() && archived.isEmpty()
}

/**
 * One sorted inventory and folded titles per summary snapshot, reused for each keystroke and
 * content reply.
 */
class SessionSearchIndex(sessions: List<SessionSummary>) {
    private class Entry(val session: SessionSummary, val title: String, val folder: String)

    private val rows =
        sessions
            .asSequence()
            .filter { !it.blank && it.origin != "subagent" }
            .distinctBy { it.sessionId }
            .sortedWith(
                compareByDescending<SessionSummary> { it.updatedAt }.thenBy { it.sessionId },
            )
            .map {
                Entry(
                    it,
                    foldSessionSearch(it.title.orEmpty().trim()),
                    foldSessionSearch(sessionFolder(it.cwd).orEmpty()),
                )
            }
            .toList()
    private val byId = rows.associateBy { it.session.sessionId }
    val showFolders =
        rows.asSequence().map { sessionFolder(it.session.cwd) }.distinct().take(2).count() > 1

    fun search(
        query: String,
        archived: Set<String>,
        hits: List<SessionContentHit> = emptyList(),
    ): SessionSearchResults {
        val terms = sessionSearchTerms(query)
        if (terms.isEmpty()) return SessionSearchResults()
        val snippets = LinkedHashMap<String, String>()
        hits.forEach { snippets.putIfAbsent(it.sessionId, it.snippet) }
        val activeRows = persistentListOf<SessionSearchHit>().builder()
        val archivedRows = persistentListOf<SessionSearchHit>().builder()
        val included = HashSet<String>()
        fun add(entry: Entry, byTitle: Boolean) {
            val row = entry.session
            val item =
                SessionSearchHit(row, row.sessionId in archived, byTitle, snippets[row.sessionId])
            if (item.archived) archivedRows.add(item) else activeRows.add(item)
            included.add(row.sessionId)
        }
        for (entry in rows) if (
            terms.all { it in entry.title || showFolders && it in entry.folder }
        )
            add(entry, true)
        for ((id, _) in snippets) if (id !in included) byId[id]?.let { add(it, false) }
        return SessionSearchResults(terms, activeRows.build(), archivedRows.build(), showFolders)
    }
}

/**
 * `\s`, `\S` and friends follow Unicode. The JVM needs the flag for that; Android's ICU rejects it
 * and is Unicode-aware already.
 */
fun unicodeRegex(pattern: String): Regex =
    try {
        java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
            .toRegex()
    } catch (_: IllegalArgumentException) {
        Regex(pattern)
    }
