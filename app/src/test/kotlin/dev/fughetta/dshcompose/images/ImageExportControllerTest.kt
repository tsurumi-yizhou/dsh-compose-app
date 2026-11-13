package dev.fughetta.dshcompose.images

import dev.fughetta.dshcompose.ui.*
import dev.fughetta.dshcompose.ui.markdown.ReplyImageRequest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class ImageExportControllerTest {
    private suspend fun Channel<ImageExportUiState?>.next(
        match: (ImageExportUiState) -> Boolean,
    ): ImageExportUiState =
        withTimeout(5000) {
            while (true) {
                val state = receive()
                if (state != null && match(state)) return@withTimeout state
            }
            @Suppress("UNREACHABLE_CODE") error("No result")
        }

    @Test
    fun permissionWaitKeepsOneOriginalFileAndClaimsThePromptOnceBeforeSaving() = runBlocking {
        val root = Files.createTempDirectory("image-save-").toFile()
        val states = Channel<ImageExportUiState?>(Channel.UNLIMITED)
        val cleaned = CompletableDeferred<Unit>()
        var staged = 0
        var granted = false
        var saved = 0
        val bytes = byteArrayOf(1, 2, 3, 4)
        val controller =
            ImageExportController(
                this,
                stage = {
                    staged++
                    val file = File(root, "original.png").apply { writeBytes(bytes) }
                    StagedImage(file, "original.png", "image/png") {
                        file.delete()
                        cleaned.complete(Unit)
                    }
                },
                save = { image ->
                    assertArrayEquals(bytes, image.file.readBytes())
                    saved++
                },
                share = { error("Not sharing") },
                hasPermission = { granted },
                legacyPermission = { true },
                publish = { states.trySend(it) },
            )
        try {
            controller.start(
                ReplyImageRequest("session", "chart.png", 1),
                ImageExportOperation.Save,
            )
            val waiting = states.next { it.needsPermission }
            assertTrue(File(root, "original.png").exists())
            assertTrue(controller.claimPermission(waiting.id))
            assertFalse(controller.claimPermission(waiting.id))
            granted = true
            controller.permissionResult(true)
            assertFalse(controller.claimPermission(waiting.id))
            val complete = states.next { it.saved }
            assertFalse(complete.busy)
            withTimeout(5000) { cleaned.await() }
            assertEquals(1, staged)
            assertEquals(1, saved)
            assertFalse(File(root, "original.png").exists())
        } finally {
            controller.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancelledPermissionCannotSaveAndShareIsPresentedOnceWithItsOwnRetainedCopy() = runBlocking {
        val root = Files.createTempDirectory("image-share-").toFile()
        val states = Channel<ImageExportUiState?>(Channel.UNLIMITED)
        val cleaned = Channel<Unit>(Channel.UNLIMITED)
        var saved = 0
        var sequence = 0
        val controller =
            ImageExportController(
                this,
                stage = {
                    val file =
                        File(root, "staged-${sequence++}.gif").apply {
                            writeBytes(byteArrayOf(7, 8, 9))
                        }
                    StagedImage(file, "chart.gif", "image/gif") {
                        file.delete()
                        cleaned.trySend(Unit)
                    }
                },
                save = { saved++ },
                share = {
                    val file = GalleryImages.shareCopy(it, File(root, "shared"))
                    ImageShareFile(file.path, it.mime, it.name)
                },
                hasPermission = { false },
                legacyPermission = { true },
                publish = { states.trySend(it) },
            )
        try {
            controller.start(
                ReplyImageRequest("session", "chart.gif", 1),
                ImageExportOperation.Save,
            )
            val waiting = states.next { it.needsPermission }
            assertTrue(controller.claimPermission(waiting.id))
            controller.dismiss()
            controller.permissionResult(true)
            withTimeout(5000) { cleaned.receive() }
            assertEquals(0, saved)
            controller.start(
                ReplyImageRequest("session", "chart.gif", 1),
                ImageExportOperation.Share,
            )
            val ready = states.next { it.share != null }
            assertFalse(ready.needsPermission)
            val shared = File(ready.share!!.path)
            withTimeout(5000) { cleaned.receive() }
            assertTrue(shared.exists())
            assertTrue(controller.claimShare(ready.id))
            assertFalse(controller.claimShare(ready.id))
            controller.shared(ready.id, true)
            assertTrue(states.next { it.shared }.share == null)
            controller.dismiss()
            assertArrayEquals(byteArrayOf(7, 8, 9), shared.readBytes())
        } finally {
            controller.close()
            root.deleteRecursively()
        }
    }
}
