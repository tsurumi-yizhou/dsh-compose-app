package dev.fughetta.dshcompose.ui.dynamicui

import dev.fughetta.dshcompose.domain.chat.ChatItem
import dev.fughetta.dshcompose.domain.chat.ChatRole
import dev.fughetta.dshcompose.ui.composer.*
import dev.fughetta.dshcompose.ui.markdown.*
import java.util.Base64
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenuiTest {
    private val pixel =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jFzYAAAAASUVORK5CYII="

    private fun view(source: String) = DynamicUiParser.parse(source).view!!

    @Test
    fun statusReportBecomesNativeContentAndCannotUseTheDynamicUiReplyOrResendPaths() {
        val source =
            """
            {"title":"Weekly build","items":[
              {"type":"grid","cols":3,"items":[{"type":"stat","label":"Passed","value":"26","delta":"+4"},{"type":"stat","label":"Failed","value":"2","delta":"-1"}]},
              {"type":"table","columns":["Task","Status","Time"],"rows":[["lint","Passed",42],["test","Failed",318]]},
              {"type":"steps","current":1,"steps":[{"title":"Fetch"},{"title":"Test","desc":"Shard 2 failed"},{"title":"Publish"}]},
              {"type":"callout","tone":"warning","title":"Notice","content":"**test** has [logs](https://ci.example/1) with ==timeouts=="},
              {"type":"echart","data":[]},
              {"type":"button","label":"Rerun","action":"hidden-action"},
              {"type":"submit","label":"Submit","action":"hidden-submit"}
            ]}
            """
                .trimIndent()
        val report = view(source)
        assertEquals("genui", report.dialect)
        assertEquals("Weekly build", report.title)
        val nodes = report.root.walk().toList()
        assertEquals(listOf("26", "2"), nodes.filterIsInstance<UiMetric>().map { it.value })
        assertEquals(
            listOf(UiTone.Good, UiTone.Bad),
            nodes.filterIsInstance<UiMetric>().map { it.tone },
        )
        assertEquals(
            listOf("Task", "Status", "Time"),
            nodes.filterIsInstance<UiTable>().single().columns.map { it.text },
        )
        assertEquals(
            listOf(UiStepStatus.Done, UiStepStatus.Current, UiStepStatus.Todo),
            nodes.filterIsInstance<UiSteps>().single().items.map { it.status },
        )
        val content = nodes.filterIsInstance<UiText>().last().rich!!
        assertEquals("test has logs with timeouts", content.text)
        assertEquals(
            "test",
            content.text.substring(content.spans.single().start, content.spans.single().end),
        )
        assertEquals(listOf(UiWebKind.Chart), nodes.filterIsInstance<UiWebOnly>().map { it.kind })
        assertEquals("Rerun", nodes.filterIsInstance<UiGenuiButton>().single().label)
        assertEquals("hidden-submit", nodes.filterIsInstance<UiGenuiSubmit>().single().action)
        assertTrue(nodes.none { it is UiOptions || it is UiConfirm || it is UiPlaceholder })
        val row = ChatItem("m1.1", ChatRole.Assistant, "```dsh-ui\n$source\n```", seq = 1)
        assertNull(
            dynamicUiReplyText(
                row,
                source,
                "spec.items[5]",
                emptyList(),
                null,
                true,
                UiReplyLabels("Select", "Other", "Confirm", "Cancel"),
            ),
        )
        assertNull(dynamicUiResendText(row, source, { "Error" }, { it }))
        val bare =
            MarkdownDocuments.parse(source, ReferenceLabelIndex.Empty).blocks.single()
                as MarkdownBlock.Visual
        assertEquals("genui", bare.parsed!!.view!!.dialect)
    }

    @Test
    fun formattedDataAndSourcesKeepVisibleContentWithoutLiveLinksOrNestedCards() {
        val report =
            view(
                """
                {"items":[
                  {"type":"hero","value":"12345","subtitle":"**Health**","spark":[1,2,3]},
                  {"type":"card","eyebrow":"Summary","title":"**Today**","accent":"#225588","items":[{"type":"progress","value":64,"variant":"ring","label":"Prepared","valueLabel":"64 / 100"}]},
                  {"type":"list","items":["[A](https://example.com)",{"title":"B","desc":"![alt](https://example.com/remote.png)"}]},
                  {"type":"keyvalue","pairs":[{"key":"Enabled","value":true}]},
                  {"type":"timeline","items":[{"time":"10:00","title":"Started","desc":"**Ready**"}]},
                  {"type":"code","lang":"kotlin","code":"val count = 1"},
                  {"type":"json","value":{"count":2}},
                  {"type":"diff","diffs":[{"path":"a.kt","oldText":"old\n","newText":"new\n"}]},
                  {"type":"copy","label":"Copy this","text":"```dsh-ui\n{}\n```"},
                  {"type":"link","label":"External label","href":"javascript:alert(1)"},
                  {"type":"badge","label":"Stable","tone":"success"},
                  {"type":"avatar","name":"Ada"},
                  {"type":"breadcrumb","items":["Root","Child"]},
                  {"type":"divider","label":"End"},{"type":"spacer"}
                ]}
                """
                    .trimIndent(),
            )
        val nodes = report.root.walk().toList()
        val metric = nodes.filterIsInstance<UiMetric>().single()
        assertEquals("12,345", metric.value)
        assertTrue(metric.display)
        assertEquals(listOf(1.0, 2.0, 3.0), metric.trend)
        assertEquals(0.64f, nodes.filterIsInstance<UiProgress>().single().fraction)
        val lists = nodes.filterIsInstance<UiList>()
        assertEquals(listOf("A", "B"), lists[0].items.map { it.title.text })
        assertEquals("alt", lists[0].items[1].subtitle!!.text)
        assertEquals("✓", lists[1].items.single().subtitle!!.text)
        assertTrue(lists[2].timeline)
        assertEquals("10:00 · Started", lists[2].items.single().title.text)
        val code = nodes.filterIsInstance<UiCode>()
        assertEquals(listOf("kotlin", "json", "diff", ""), code.map { it.language })
        assertEquals("--- a.kt\n-old\n+new", code[2].code)
        assertEquals("```dsh-ui\n{}\n```", code[3].code)
        assertTrue(nodes.filterIsInstance<UiText>().any { it.rich?.text == "External label" })
        assertEquals(0x225588L, nodes.filterIsInstance<UiPanel>().first { it.tint != null }.tint)
        assertTrue(nodes.none { it is UiPicture || it is UiOptions || it is UiConfirm })
    }

    @Test
    fun pageAndFoldModelsKeepDistinctImagePathsAndOnlyMountedPicturesEnterTheOpenedGallery() {
        val source =
            """
            {"items":[
              {"type":"tabs","tabs":[{"label":"First","items":[{"type":"image","src":"$pixel","alt":"one"}]},{"label":"Second","items":[{"type":"image","src":"$pixel","alt":"two"}]}]},
              {"type":"accordion","items":[{"title":"Hidden","items":[{"type":"image","src":"$pixel","alt":"three"}]}]},
              {"type":"carousel","items":[{"type":"text","content":"Page A"},{"type":"text","content":"Page B"}]},
              {"type":"row","items":[{"type":"col","width":25,"items":[{"type":"text","content":"Left"}]},{"type":"col","width":75,"items":[{"type":"text","content":"Right"}]}]}
            ]}
        """
                .trimIndent()
        val report = view(source)
        val nodes = report.root.walk().toList()
        assertEquals(
            listOf(UiBrowseKind.Tabs, UiBrowseKind.Accordion, UiBrowseKind.Carousel),
            nodes.filterIsInstance<UiBrowse>().map { it.kind },
        )
        val weighted = nodes.filterIsInstance<UiLayout>().single { it.weights != null }
        assertEquals(listOf(3f, 9f), weighted.weights)
        val pictures = nodes.filterIsInstance<UiPicture>()
        assertEquals(listOf("one", "two", "three"), pictures.map { it.alt })
        assertEquals(3, pictures.map { it.path }.distinct().size)
        assertTrue(pictures.all { it.source == pixel })
        val gallery = pictures.map { MarkdownGalleryImage(it.source!!, it.alt) }.toPersistentList()
        val opened = replyGallerySelection(gallery, listOf(0), 0)!!
        assertEquals(listOf("one"), opened.images.map { it.alt })
    }

    @Test
    fun seriesAlignByCategoryAndKeepFirstDuplicateWhileBrokenChartsRemainQuietWebContent() {
        val report =
            view(
                """
                {"items":[{"type":"chart","horizontal":true,"series":[
                  {"label":"First","data":[{"label":"A","value":3},{"label":"A","value":99},{"label":"B","value":5}]},
                  {"label":"Second","data":[{"label":"B","value":7},{"label":"C","value":9}]}]},
                  {"type":"chart","data":[{"label":"Unknown","value":null}]}]}
                """
                    .trimIndent(),
            )
        val chart = report.root.walk().filterIsInstance<UiChart>().single()
        assertEquals(UiChartKind.Horizontal, chart.kind)
        assertEquals(listOf("A", "B", "C"), chart.categories)
        assertEquals(listOf(3.0, 5.0, null), chart.series[0].values)
        assertEquals(listOf(null, 7.0, 9.0), chart.series[1].values)
        assertEquals(
            UiWebKind.Chart,
            report.root.walk().filterIsInstance<UiWebOnly>().single().kind,
        )
    }

    @Test
    fun rasterPolicyRejectsExternalAndOversizedImagesBeforeDecodingAndKeepsSafeInlinePictures() {
        val valid = Base64.getDecoder().decode(pixel.substringAfter(','))
        assertEquals(1 to 1, rasterSize(valid))
        val huge =
            valid.copyOf().apply {
                this[16] = 0
                this[17] = 0
                this[18] = 16
                this[19] = 1
            }
        val giant = "data:image/png;base64," + Base64.getEncoder().encodeToString(huge)
        assertNull(inlinePicture(giant))
        assertNull(inlinePicture("data:image/svg+xml;base64,PHN2Zy8+"))
        assertNull(inlinePicture("data:image/png;base64,invalid"))
        val report =
            view(
                """
            {"items":[{"type":"image","src":"https://example.com/private.png"},{"type":"image","src":"/mmx-files/private.png"},
              {"type":"image","src":"$giant","alt":"Too large"},{"type":"image","src":"$pixel","alt":"Safe"},
              {"type":"image-group","title":"Pictures","items":[{"src":"https://example.com/ignored.png"},{"src":"$pixel","alt":"Grouped"}]}]}
        """
                    .trimIndent(),
            )
        val pictures = report.root.walk().filterIsInstance<UiPicture>().toList()
        assertEquals(listOf(null, pixel, pixel), pictures.map { it.source })
        assertEquals(listOf("Too large", "Safe", "Grouped"), pictures.map { it.alt })
        assertEquals(listOf(null, 1f, 1f), pictures.map { it.aspect })
        assertTrue(report.root.walk().any { it is UiGrid })
        assertEquals(1, report.root.walk().filterIsInstance<UiWebOnly>().count())
    }

    @Test
    fun mappedTreesAndCollectionsKeepTheirRenderingBudgets() {
        val texts = (0 until 32).joinToString(",") { """{"type":"text","content":"$it"}""" }
        val cards =
            (0 until 4).joinToString(",") {
                """{"type":"card","title":"Card $it","items":[$texts]}"""
            }
        val report = view("""{"items":[$cards]}""")
        assertTrue(report.root.walk().count() <= 120)
        assertTrue(report.issues.any { it.kind == UiIssueKind.Truncated })
        val columns = (0 until 9).joinToString(",") { "\"C$it\"" }
        val row = (0 until 9).joinToString(",") { "\"$it\"" }
        val rows = (0 until 60).joinToString(",") { "[$row]" }
        val tableReport = view("""{"type":"table","columns":[$columns],"rows":[$rows]}""")
        val table = tableReport.root.walk().filterIsInstance<UiTable>().single()
        assertEquals(8, table.columns.size)
        assertEquals(50, table.rows.size)
        assertTrue(tableReport.issues.any { it.kind == UiIssueKind.Truncated })
    }
}
