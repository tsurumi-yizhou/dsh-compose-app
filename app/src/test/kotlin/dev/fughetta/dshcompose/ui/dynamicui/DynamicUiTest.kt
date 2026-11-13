package dev.fughetta.dshcompose.ui.dynamicui

import dev.fughetta.dshcompose.data.transcript.ChatTranscript
import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.protocol.*
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.markdown.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DynamicUiTest {
    @Test
    fun dashboardRendersTheCatalogAndPreservesUsableSiblingsOfBrokenChildren() {
        val parsed =
            DynamicUiParser.parse(
                """
                { // model-authored dashboard
                  "type":"stack", "title":"Report", "children":{"item":[
                    {"type":"row","children":[{"type":"metric","label":"Requests","value":"12000","tone":"good"}]},
                    {"type":"text","text":"**Result**"},
                    {"type":"chart","categories":["A","B"],"series":{"values":["1,200","35%"]}},
                    {"type":"compare","items":[{"title":"Plan","rows":{"Cost":"Low"}}]},
                    {"type":"steps","items":["Prepare",{"label":"Run","status":"active"}]},
                    {"type":"callout","text":"Check the values","tone":"warning"},
                    {"type":"options","id":"invisible","options":[null,{"label":"A","value":"hidden"},"B"],"multi":true,"freetext":"Your choice"},
                    {"type":"confirm","confirmLabel":"Continue"},
                    {"type":"not-a-component"},
                  ]}
                }
                """
                    .trimIndent(),
            )
        val view = parsed.view!!
        val layout = view.root as UiLayout
        assertEquals("Report", view.title)
        assertEquals(9, layout.children.size)
        assertEquals(
            "12,000",
            ((layout.children[0] as UiLayout).children.single() as UiMetric).value,
        )
        assertEquals(listOf(1200.0, 35.0), (layout.children[2] as UiChart).series.single().values)
        assertEquals(listOf("Cost" to "Low"), (layout.children[3] as UiCompare).items.single().rows)
        assertEquals(UiStepStatus.Current, (layout.children[4] as UiSteps).items.last().status)
        assertTrue((layout.children[6] as UiOptions).freetext)
        assertTrue(layout.children.last() is UiPlaceholder)
        assertEquals(
            setOf(UiIssueKind.Unknown, UiIssueKind.Required),
            view.issues.map { it.kind }.toSet(),
        )
    }

    @Test
    fun renderingBudgetsRetainPartialContentButRejectOversizedAndDeepSourcesBeforeDecode() {
        assertNull(DynamicUiParser.parse("{broken}").view)
        assertEquals(
            UiIssueKind.Size,
            DynamicUiParser.parse("""{"type":"text","text":"${"中".repeat(6000)}"}""")
                .issues
                .single()
                .kind,
        )
        assertEquals(
            UiIssueKind.Depth,
            DynamicUiParser.parse("[".repeat(65) + "0" + "]".repeat(65)).issues.single().kind,
        )
        val children = (0 until 16).joinToString(",") { """{"type":"text","text":"$it"}""" }
        val rows = (0 until 4).joinToString(",") { """{"type":"row","children":[$children]}""" }
        val view = DynamicUiParser.parse("""{"type":"stack","children":[$rows]}""").view!!
        assertTrue(view.root.walk().count() <= 40)
        assertTrue(view.issues.any { it.kind == UiIssueKind.Truncated })
        val block =
            MarkdownDocuments.parse(
                    "```dsh-ui\n{\"type\":\"metric\",\"label\":\"Count\",\"value\":4}\n```",
                    ReferenceLabelIndex.Empty,
                )
                .blocks
                .single() as MarkdownBlock.Visual
        assertTrue(block.closed)
        assertTrue(block.parsed!!.view!!.root is UiMetric)
        val partial =
            MarkdownDocuments.parse("```dsh-ui\n{\"type\":\"metric\"", ReferenceLabelIndex.Empty)
                .blocks
                .single() as MarkdownBlock.Visual
        assertFalse(partial.closed)
        assertNull(partial.parsed)
    }

    @Test
    fun chartGeometryKeepsGapsAndFiniteCoordinatesForNegativeAndHugeValues() {
        val line =
            DynamicUiParser.parse(
                    """{"type":"chart","kind":"line","categories":["Low","Gap","High"],"values":[-1e308,null,1e308]}""",
                )
                .view!!
                .root as UiChart
        val scale = chartScale(line)
        assertEquals(0f, scale.fraction(-1e308))
        assertEquals(0.5f, scale.fraction(0.0))
        assertEquals(1f, scale.fraction(1e308))
        assertNull(line.series.single().values[1])
        val donut =
            DynamicUiParser.parse(
                    """{"type":"chart","kind":"pie","categories":["Ignored","Empty","A","B"],"values":[-1,null,1e308,1e308]}""",
                )
                .view!!
                .root as UiChart
        assertEquals(listOf(0f, 0f, 0.5f, 0.5f), donutWeights(donut))
        assertEquals("1E+308", uiNumber(1e308))
        val validAfterMalformed =
            DynamicUiParser.parse(
                    """{"type":"chart","categories":["A"],"series":[{},{},{},{},{},{},{"values":[5]}]}""",
                )
                .view!!
                .root as UiChart
        assertEquals(listOf(5.0), validAfterMalformed.series.single().values)
    }

    @Test
    fun selectedRepliesUseVisibleLabelsAndRestoreBothInterfaceLanguages() {
        val source =
            """{"title":"Decision","type":"options","options":[{"label":"Alpha、Beta","value":"hidden-value"},"Gamma"],"multi":true,"freetext":true,"id":"hidden-id"}"""
        val view = DynamicUiParser.parse(source).view!!
        val reply = selectedReply(view, "view", listOf(1, 0), "Custom\nanswer", null)!!
        for (labels in
            listOf(
                UiReplyLabels("Select", "Other", "Confirm", "Cancel"),
                UiReplyLabels("选择", "其他", "确认", "取消"),
            )) {
            val text = uiReplyText(reply, labels)
            assertFalse(text.contains("hidden"))
            val restored = parseUiReply(text)!!
            assertEquals(reply, restored)
            assertEquals(setOf(0, 1), answerFor(view, restored)!!.selected)
            assertEquals("Custom answer", restored.freetext)
        }
        assertNull(selectedReply(view, "view", listOf(0, 0), null, null))
        assertNull(selectedReply(view, "view", listOf(2), null, null))
        assertNull(selectedReply(view, "view", emptyList(), null, true))
        assertNull(answerFor(view, UiReply(UiReplyKind.Choice, "Different", "Gamma")))
        val row = ChatItem("m1.1", ChatRole.Assistant, "```dsh-ui\n$source\n```", seq = 1)
        val labels = UiReplyLabels("Select", "Other", "Confirm", "Cancel")
        assertNotNull(dynamicUiReplyText(row, source, "view", listOf(0), null, null, labels))
        assertNull(
            dynamicUiReplyText(
                row.copy(text = "```json\n$source\n```"),
                source,
                "view",
                listOf(0),
                null,
                null,
                labels,
            ),
        )
        assertNull(
            dynamicUiReplyText(
                row.copy(text = "```dsh-ui\n$source"),
                source,
                "view",
                listOf(0),
                null,
                null,
                labels,
            ),
        )
        assertNull(
            dynamicUiReplyText(
                row,
                source.replace("Decision", "Replaced"),
                "view",
                listOf(0),
                null,
                null,
                labels,
            ),
        )
    }

    @Test
    fun indexedMessageOrderMakesHistoryReadOnlyAndUsesTheFirstFollowingUserReply() {
        val transcript = ChatTranscript()
        fun event(seq: Long, type: String, data: String) =
            transcript.apply(
                SessionUpdate.Record(
                    SessionRecord(seq, seq, type, Json.parseToJsonElement(data).jsonObject),
                ),
            )
        event(
            1,
            "assistant/message",
            """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"First view"}]}}""",
        )
        val first = transcript.items.single()
        assertEquals(
            UiInteraction.Live,
            uiInteraction(first, transcript.presentation.order, false).mode,
        )
        assertEquals(
            UiInteraction.Running,
            uiInteraction(first, transcript.presentation.order, true).mode,
        )
        event(
            2,
            "assistant/message",
            """{"turn":1,"step":2,"message":{"content":[{"type":"text","text":"Second view"}]}}""",
        )
        assertEquals(
            UiInteraction.Expired,
            uiInteraction(first, transcript.presentation.order, false).mode,
        )
        event(
            3,
            "user/message",
            """{"source":{"kind":"user"},"content":[{"type":"text","text":"[dsh-ui reply] Decision\nSelect: Gamma"}]}""",
        )
        event(
            4,
            "user/message",
            """{"source":{"kind":"user"},"content":[{"type":"text","text":"Later ordinary message"}]}""",
        )
        val interaction = uiInteraction(first, transcript.presentation.order, false)
        assertEquals(UiInteraction.Answered, interaction.mode)
        assertEquals("Gamma", interaction.reply!!.value)
        assertEquals(
            UiInteraction.Expired,
            uiInteraction(first.copy(key = "stale"), transcript.presentation.order, false).mode,
        )
    }
}
