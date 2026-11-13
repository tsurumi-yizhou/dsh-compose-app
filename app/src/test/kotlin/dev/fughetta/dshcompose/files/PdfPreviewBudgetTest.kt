package dev.fughetta.dshcompose.files

import dev.fughetta.dshcompose.ui.pdfPreviewSize
import org.junit.Assert.*
import org.junit.Test

class PdfPreviewBudgetTest {
    @Test
    fun portraitLandscapeAndOversizedPagesStayWithinOneBoundedCanvas() {
        for ((width, height) in listOf(612 to 792, 792 to 612, 50000 to 200)) {
            val (renderWidth, renderHeight) = pdfPreviewSize(width, height)
            assertTrue(renderWidth in 1..2048)
            assertTrue(renderHeight in 1..2048)
            assertTrue(renderWidth.toLong() * renderHeight * 4 <= 16L * 1024 * 1024)
            assertEquals(width > height, renderWidth > renderHeight)
        }
    }
}
