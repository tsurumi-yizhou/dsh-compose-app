package dev.fughetta.dshcompose.images

import dev.fughetta.dshcompose.data.attachments.AttachmentMetadata
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GalleryImagesTest {
    @Test
    fun galleryKeepsOriginalGifBytesAndSafeNamesWhileShareCacheEvictsOldEntriesOnlyWithinItsRoot() {
        val root = Files.createTempDirectory("gallery-image-").toFile()
        try {
            val original =
                File(root, "animation").apply { writeBytes("GIF89a-original-frames".toByteArray()) }
            assertEquals(
                "image/gif",
                AttachmentMetadata.imageMime(original),
            )
            val name = GalleryImages.name("folder/animation.jpeg", "image/gif")
            assertEquals("animation.gif", name)
            assertEquals("chart_.png", GalleryImages.name("C:\\folder\\chart?.jpg", "image/png"))
            val longName =
                GalleryImages.name("a".repeat(79) + "😀tail", "image/png").substringBeforeLast('.')
            assertEquals(79, longName.length)
            val shared = File(root, "shared").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val expired = File(shared, "expired").apply { mkdirs() }
            File(expired, "old.png").writeText("expired")
            expired.setLastModified(now - 25 * 60 * 60 * 1000L)
            for (index in 0..4) {
                val entry = File(shared, "recent-$index").apply { mkdirs() }
                RandomAccessFile(File(entry, "image.png"), "rw").use {
                    it.setLength(20L * 1024 * 1024)
                }
                entry.setLastModified(now - 1000L + index)
            }
            val result =
                GalleryImages.shareCopy(StagedImage(original, name, "image/gif"), shared, now)
            assertFalse(expired.exists())
            assertFalse(File(shared, "recent-0").exists())
            assertTrue(File(shared, "recent-4").exists())
            assertArrayEquals(original.readBytes(), result.readBytes())
            assertTrue(original.exists())
            assertEquals("animation.gif", result.name)
        } finally {
            root.deleteRecursively()
        }
    }
}
