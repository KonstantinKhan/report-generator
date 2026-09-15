package dev.reportgenerator.renderpdf

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrColumn
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableHeader
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.layout.FontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfRendererTest {

    private fun loadFontBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) {
            "test font resource missing"
        }.readBytes()

    private fun buildFixturePdf(): ByteArray {
        val registry = FontRegistry()
        val fontRef = registry.register("pt-sans", loadFontBytes())
        val textMeasurer = PdfBoxTextMeasurer(registry) { fontRef }

        val columns = listOf(IrColumn("name", 40.mm))
        val header = IrTableHeader(listOf(IrCell("Наименование")))
        val table = IrTable(
            columns = columns,
            header = header,
            content = listOf(
                IrGroup("Детали", listOf(IrRow(listOf(IrCell("Вал"))), IrRow(listOf(IrCell("Втулка")))))
            )
        )
        val document = IrDocument(
            pageSetup = PageSetup(
                format = PageFormat("snapshot", width = 100.mm, height = 60.mm),
                margins = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 10.mm)
            ),
            elements = listOf(table)
        )

        val laidOut = layOut(document, textMeasurer) { fontRef }
        return renderToPdf(laidOut, registry)
    }

    @Test
    fun `pdf has one page at the declared size`() {
        val bytes = buildFixturePdf()
        Loader.loadPDF(bytes).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val box = pdf.getPage(0).mediaBox
            assertEquals(100.0 * 72.0 / 25.4, box.width.toDouble(), 0.5)
            assertEquals(60.0 * 72.0 / 25.4, box.height.toDouble(), 0.5)
        }
    }

    @Test
    fun `rasterized page matches golden snapshot pixel for pixel`() {
        val bytes = buildFixturePdf()
        val rendered = Loader.loadPDF(bytes).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 150f)
        }

        val golden = File("src/test/resources/snapshots/simple-table.png")
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            ImageIO.write(rendered, "png", golden)
        }

        val expected = ImageIO.read(golden)
        assertEquals(expected.width, rendered.width)
        assertEquals(expected.height, rendered.height)

        var mismatches = 0
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                if (expected.getRGB(x, y) != rendered.getRGB(x, y)) mismatches++
            }
        }
        assertEquals(0, mismatches, "$mismatches pixel(s) differ from golden snapshot")
    }

    @Test
    fun `rasterized page is not blank`() {
        val bytes = buildFixturePdf()
        val rendered = Loader.loadPDF(bytes).use { pdf ->
            PDFRenderer(pdf).renderImageWithDPI(0, 150f)
        }

        var nonWhitePixels = 0
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                if (rendered.getRGB(x, y) != -1) nonWhitePixels++
            }
        }
        assertTrue(nonWhitePixels > 100, "expected visible ink, found $nonWhitePixels non-white pixels")
    }
}
