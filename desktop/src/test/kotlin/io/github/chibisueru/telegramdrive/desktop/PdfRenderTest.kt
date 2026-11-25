package io.github.chibisueru.telegramdrive.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PDF viewer's rendering path: load a file, render a page at screen width. */
class PdfRenderTest {
    @Test fun rendersPages() {
        val f = File.createTempFile("tgdrive", ".pdf").apply { deleteOnExit() }
        PDDocument().use { doc ->
            repeat(2) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    cs.newLineAtOffset(72f, 700f); cs.showText("Page ${i + 1}"); cs.endText()
                }
            }
            doc.save(f)
        }
        Loader.loadPDF(f).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val img = PDFRenderer(doc).renderImageWithDPI(0, 96f, ImageType.RGB)
            assertTrue(img.width in 793..794, "A4 at 96 dpi, got ${img.width}")
            // Some dark pixels where the text is.
            var dark = 0
            for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) if ((img.getRGB(x, y) and 0xFF) < 100) dark++
            assertTrue(dark > 10, "text should be drawn")
        }
    }
}
