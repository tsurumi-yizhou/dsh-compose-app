package dev.fughetta.dshcompose.ui.composer

import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceEditingTest {
    @Test
    fun sentMessagesResolveLongestKnownLabelsWithoutStealingCanonicalOrFileReferences() {
        val index =
            ReferenceLabelIndex.build(
                sequenceOf("task", "task plan", "notes.md", "中文任务", "\ninvalid"),
            )
        val raw =
            "@task plan， @tasking @unknown a@task @notes.md @中文任务。 @[canonical](dsh-session:id) @src/main.kt @\"folder space/"
        val spans = parseMessageReferences(raw, index)
        assertEquals(
            listOf("task plan", "notes.md", "中文任务", "canonical", "main.kt", "folder space/"),
            spans.map { it.label },
        )
        assertEquals(
            listOf(
                ReferenceSpanKind.Conversation,
                ReferenceSpanKind.Conversation,
                ReferenceSpanKind.Conversation,
                ReferenceSpanKind.Conversation,
                ReferenceSpanKind.File,
                ReferenceSpanKind.Directory,
            ),
            spans.map { it.kind },
        )
        assertTrue(spans.zipWithNext().all { (before, after) -> before.end <= after.start })
        assertEquals("@task plan", raw.substring(spans.first().start, spans.first().end))
        assertEquals(
            listOf("main.kt"),
            parseMessageReferences("@task @src/main.kt", ReferenceLabelIndex.Empty).map {
                it.label
            },
        )
        assertEquals(
            "escaped] name",
            parseMessageReferences("@[escaped\\] name](dsh-session:id)", index).single().label,
        )
    }

    @Test
    fun committedReferencesStayAtomicWhilePastingTypingAndImeInputRemainEditable() {
        val raw = "Read @\"src/new file.kt\" then @[task](dsh-session:InMxIg) end"
        val pasted =
            editReferenceDraft(
                ReferenceInput("", 0, 0),
                ReferenceInput(raw, raw.length, raw.length),
                persistentListOf(),
                false,
            )
        assertEquals(listOf("new file.kt", "task"), pasted.spans.map { it.label })
        val file = pasted.spans.first()
        val deleted =
            editReferenceDraft(
                ReferenceInput(raw, file.end, file.end),
                ReferenceInput(raw.removeRange(file.end - 1, file.end), file.end - 1, file.end - 1),
                pasted.spans,
                false,
            )
        assertEquals("Read  then @[task](dsh-session:InMxIg) end", deleted.input.text)
        assertEquals(file.start, deleted.input.end)
        assertEquals(listOf("task"), deleted.spans.map { it.label })
        val session = deleted.spans.single()
        val selection =
            editReferenceDraft(
                deleted.input,
                deleted.input.copy(start = session.start + 1, end = session.end - 1),
                deleted.spans,
                false,
            )
        assertEquals(session.start, selection.input.start)
        assertEquals(session.end, selection.input.end)

        val inserted =
            insertReferenceDraft(
                "tail",
                persistentListOf(),
                0,
                0,
                ReferenceInsertion("@README", "README", ReferenceSpanKind.File),
            )
        assertEquals("@README tail", inserted.input.text)
        assertEquals("README", inserted.spans.single().label)
        val spaced =
            insertReferenceDraft(
                "read @x tail",
                persistentListOf(),
                5,
                7,
                ReferenceInsertion("@README", "README", ReferenceSpanKind.File),
            )
        assertEquals("read @README tail", spaced.input.text)
        assertEquals("read @README ".length, spaced.input.end)
        val prefixed =
            editReferenceDraft(
                inserted.input.copy(start = 0, end = 0),
                ReferenceInput("please @README tail", 7, 7),
                inserted.spans,
                false,
            )
        assertEquals(7, prefixed.spans.single().start)
        assertEquals("please @README tail", prefixed.input.text)
        val composing =
            editReferenceDraft(
                ReferenceInput("", 0, 0),
                ReferenceInput("@file.kt", 8, 8),
                persistentListOf(),
                true,
            )
        assertTrue(composing.spans.isEmpty())
        assertFalse(composing.widened)
        assertEquals("@file.kt", composing.input.text)
    }

    @Test
    fun shortenedLabelsKeepOffsetMappingsMonotoneAndAlignEveryReferenceBoundary() {
        val raw = "See @[task](dsh-session:InMxIg) and @\"folder/new file.kt\"."
        val spans = parseDraftReferences(raw)
        val display = referenceDisplay(raw, spans)
        assertFalse(display.text.contains("dsh-session:"))
        assertTrue(display.text.contains("@task"))
        assertTrue(display.text.contains("@new file.kt"))
        val forward = (0..raw.length).map(display::originalOffset)
        val reverse = (0..display.text.length).map(display::displayOffset)
        assertTrue(forward.all { it in 0..display.text.length })
        assertTrue(reverse.all { it in 0..raw.length })
        assertEquals(forward.sorted(), forward)
        assertEquals(reverse.sorted(), reverse)
        for (span in display.spans) {
            assertEquals(span.start, display.originalOffset(span.source.start))
            assertEquals(span.end, display.originalOffset(span.source.end))
            assertEquals(span.source.start, display.displayOffset(span.start))
            assertEquals(span.source.end, display.displayOffset(span.end))
        }
        assertEquals(display.text.length, display.originalOffset(raw.length))
        assertEquals(raw.length, display.displayOffset(display.text.length))
    }
}
