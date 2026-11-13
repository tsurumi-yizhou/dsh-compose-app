package dev.fughetta.dshcompose.domain.sessions.search

import dev.fughetta.dshcompose.domain.sessions.SessionSummary
import dev.fughetta.dshcompose.domain.sessions.search.*
import org.junit.Assert.*
import org.junit.Test

class SessionSearchTest {
    @Test
    fun largeInventoryMergesRankedContentWithoutDuplicatingOrOpeningHiddenSessions() {
        val rows =
            (0 until 20000).map { id ->
                SessionSummary(
                    "s$id",
                    when (id) {
                        0 -> "ＡＬＰＨＡ Beta"
                        1 -> "alpha beta"
                        5 -> "Beta Alpha"
                        else -> "聊天 $id"
                    },
                    if (id == 2) "/other" else "/work",
                    false,
                    id == 3,
                    true,
                    null,
                    if (id == 4) "subagent" else null,
                    if (id == 5) 100 else id.toLong(),
                )
            }
        val index = SessionSearchIndex(rows)
        val content =
            listOf(
                SessionContentHit("s2", "First matching passage"),
                SessionContentHit("s0", "Also content"),
                SessionContentHit("s2", "Duplicate"),
                SessionContentHit("s3", "Blank"),
                SessionContentHit("s4", "Subagent"),
                SessionContentHit("gone", "Removed"),
            )
        val result = index.search("ＢＥＴＡ alpha", setOf("s1"), content)
        assertEquals(listOf("s5", "s0", "s2"), result.active.map { it.session.sessionId })
        assertEquals(listOf("s1"), result.archived.map { it.session.sessionId })
        assertEquals("First matching passage", result.active.last().snippet)
        assertFalse(result.active.last().byTitle)
        assertSame(rows[0], result.active[1].session)
        val restored = index.search("alpha beta", setOf("s5"), content)
        assertEquals(listOf("s1", "s0", "s2"), restored.active.map { it.session.sessionId })
        assertEquals(
            listOf("s2"),
            index.search("other", emptySet()).active.map { it.session.sessionId },
        )
    }

    @Test
    fun widthCaseAndExcerptRangesKeepUtf16OffsetsAndNeverCutEmojiInputInHalf() {
        val text = "😀 ＡLPHA \uD801\uDC00"
        assertEquals(text.length, foldSessionSearch(text).length)
        assertEquals(
            listOf(SearchRange(3, 8), SearchRange(9, 11)),
            sessionSearchRanges(text, sessionSearchTerms("alpha \uD801\uDC28")),
        )
        assertEquals(
            listOf(SearchRange(0, 6)),
            sessionSearchRanges("banana", listOf("ban", "ana", "na")),
        )
        val snippet = "😀".repeat(35) + " alpha tail"
        val excerpt = sessionSearchExcerpt(snippet, listOf("alpha"))
        assertEquals(
            "alpha",
            excerpt.text.substring(excerpt.ranges.single().start, excerpt.ranges.single().end),
        )
        assertFalse(excerpt.text[1].isLowSurrogate())
        assertEquals(199, limitSessionSearchInput("a".repeat(199) + "😀more").length)
        assertEquals(sessionSearchKey(" A  B "), sessionSearchKey("a b"))
        assertNotEquals(sessionSearchKey("Ａ"), sessionSearchKey("A"))
    }
}
