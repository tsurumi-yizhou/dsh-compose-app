package dev.fughetta.dshcompose.data.transcript

import dev.fughetta.dshcompose.domain.media.*
import dev.fughetta.dshcompose.domain.media.MediaKind
import dev.fughetta.dshcompose.domain.media.MediaModelRef
import dev.fughetta.dshcompose.domain.media.MediaParameters
import dev.fughetta.dshcompose.domain.media.MediaStatus
import dev.fughetta.dshcompose.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MediaGenerationTest {
    @Test
    fun generationHeaderKeepsTheHostsGrammarAndDoesNotMisclassifyOrdinaryText() {
        val parameters =
            MediaParameters(
                MediaKind.Video,
                MediaModelRef("media", "ark", "seedance", "Seedance"),
                "16:9",
                seconds = 5,
                resolution = "720p",
            )
        val formatted = MediaGenerationHeader.format(parameters, "Camera moves slowly")
        assertEquals(
            "〔生成视频 · 16:9 · 5 秒 · 720p · Seedance〈ark/seedance〉〕\nCamera moves slowly",
            formatted,
        )
        val restored = MediaGenerationHeader.read(formatted)!!
        assertEquals("ark", restored.parameters?.model?.service)
        assertEquals("Camera moves slowly", restored.prompt)
        assertNull(MediaGenerationHeader.read("〔生成图像的方法〕 is ordinary text"))
        assertEquals("invalid_order", MediaGenerationHeader.read("〔生成图像 · 2 张 · 1:1〕\nCat")?.error)
        assertEquals("invalid_seconds", MediaGenerationHeader.read("〔生成视频 · 61 秒〕\nCat")?.error)
        assertEquals("missing_prompt", MediaGenerationHeader.read("〔生成图像〕\n \t")?.error)
        assertFalse(MediaGenerationHeader.expressible("16:9"))
        assertTrue(MediaGenerationHeader.expressible("Qwen Image"))
    }

    @Test
    fun mediaProjectionCoalescesVideoResultsAndSharesItsIndexDuringTokenUpdatesAndHistoryRebuilds() {
        val transcript = ChatTranscript()
        fun record(seq: Long, type: String, raw: String) =
            SessionRecord(seq, seq * 1000, type, Json.parseToJsonElement(raw).jsonObject)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    1,
                    "tool/result",
                    """{"message":{"toolCallId":"start","content":[]},"meta":{"dsh-compose/media":{"v":1,"rid":"rid","kind":"video","status":"running","job":"job"}}}""",
                ),
            ),
        )
        val initial = transcript.presentation
        assertEquals(1, initial.generatedMedia.size)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    2,
                    "tool/result",
                    """{"message":{"toolCallId":"finish","content":[]},"meta":{"dsh-compose/media":{"v":1,"rid":"rid","kind":"video","status":"done","items":[{"path":"/work/generated/movie.mp4","mime":"video/mp4","poster":"/work/generated/poster.jpg"}]}}}""",
                ),
            ),
        )
        val finished = transcript.presentation
        assertEquals(1, finished.generatedMedia.size)
        assertEquals(MediaStatus.Done, finished.generatedMedia["rid"]?.media?.status)
        assertEquals(
            setOf("/work/generated/movie.mp4", "/work/generated/poster.jpg"),
            finished.generatedMediaPaths,
        )
        assertEquals(1000L, finished.generatedMedia["rid"]?.createdAt)
        transcript.apply(
            SessionUpdate.Record(
                record(
                    3,
                    "assistant/message",
                    """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"Done"}]}}""",
                ),
            ),
        )
        assertSame(finished.generatedMedia, transcript.presentation.generatedMedia)
        assertEquals(finished.mediaRevision, transcript.presentation.mediaRevision)
        transcript.prependHistory(listOf(record(0, "turn/start", """{"turn":1}""")), false)
        assertSame(finished.generatedMedia, transcript.presentation.generatedMedia)
        assertSame(finished.generatedMediaPaths, transcript.presentation.generatedMediaPaths)
    }
}
