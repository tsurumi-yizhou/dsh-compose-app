package dev.fughetta.dshcompose.notify

import org.junit.Assert.*
import org.junit.Test

class NotificationCopyTest {
    private val words =
        NotificationWords(
            untitled = "Untitled",
            publicDone = "public done",
            publicApproval = "public approval",
            publicQuestion = "public question",
            publicStopped = "public stopped",
            publicFailed = "public failed",
            chipDone = "chip done",
            chipFailed = "chip failed",
            chipWaiting = "chip waiting",
            chipRunning = "chip running",
            livePublic = "live public",
            runningText = { others -> "running +$others" },
        )

    @Test
    fun publicTextMapsEveryKindAndFallsBackToFailed() {
        assertEquals("public done", NotificationCopy.publicText("done", words))
        assertEquals("public approval", NotificationCopy.publicText("approval", words))
        assertEquals("public question", NotificationCopy.publicText("question", words))
        assertEquals("public stopped", NotificationCopy.publicText("stopped", words))
        assertEquals("public failed", NotificationCopy.publicText("failed", words))
        assertEquals("public failed", NotificationCopy.publicText("something-new", words))
    }

    @Test
    fun titleFallsBackOnlyForMissingOrBlankCachedTitles() {
        assertEquals("Cached", NotificationCopy.title("Cached", words))
        assertEquals("Untitled", NotificationCopy.title("", words))
        assertEquals("Untitled", NotificationCopy.title("   ", words))
        assertEquals("Untitled", NotificationCopy.title(null, words))
    }

    @Test
    fun liveContentTruncatesAndPicksTheChipForTheKind() {
        val long = "x".repeat(2_000)

        val waiting =
            NotificationCopy.liveContent(
                kind = LiveContent.Kind.WAITING,
                title = long,
                text = long,
                publicText = "locked",
                sessionId = "s",
                host = "h",
                eventId = "e",
                words = words,
            )
        assertEquals(LiveContent.TITLE_MAX, waiting.title.length)
        assertEquals(LiveContent.TEXT_MAX, waiting.text.length)
        assertEquals("chip waiting", waiting.chip)
        assertEquals("locked", waiting.publicText)
        assertEquals("s", waiting.sessionId)
        assertEquals("h", waiting.host)

        assertEquals(
            "chip done",
            NotificationCopy.liveContent(
                    LiveContent.Kind.DONE,
                    "t",
                    "x",
                    "p",
                    null,
                    null,
                    null,
                    words,
                )
                .chip,
        )
        assertEquals(
            "chip failed",
            NotificationCopy.liveContent(
                    LiveContent.Kind.FAILED,
                    "t",
                    "x",
                    "p",
                    null,
                    null,
                    null,
                    words,
                )
                .chip,
        )
        assertEquals(
            "chip running",
            NotificationCopy.liveContent(
                    LiveContent.Kind.RUNNING,
                    "t",
                    "x",
                    "p",
                    null,
                    null,
                    null,
                    words,
                )
                .chip,
        )
    }

    @Test
    fun runningContentCarriesTheClockAndTheChip() {
        val content =
            NotificationCopy.runningContent(
                title = "Title",
                text = "running +2",
                since = 42L,
                sessionId = "root",
                host = "host",
                words = words,
            )

        assertEquals(LiveContent.Kind.RUNNING, content.kind)
        assertEquals("Title", content.title)
        assertEquals("running +2", content.text)
        assertEquals(42L, content.sinceMs)
        assertEquals("chip running", content.chip)
        assertEquals("live public", content.publicText)
    }
}
