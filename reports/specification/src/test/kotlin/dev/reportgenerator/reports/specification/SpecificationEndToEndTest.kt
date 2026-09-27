package dev.reportgenerator.reports.specification

import dev.reportgenerator.api.ItemDto
import dev.reportgenerator.api.SpecificationDto
import dev.reportgenerator.data.mapToSpecificationData
import dev.reportgenerator.layout.FontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import org.apache.pdfbox.Loader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecificationEndToEndTest {

    private fun loadFontBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")) {
            "test font resource missing"
        }.readBytes()

    private fun fixtureData() = mapToSpecificationData(
        SpecificationDto(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            items = listOf(
                ItemDto("AAA.01.000", "Корпус", "ASSEMBLY", 1.0),
                ItemDto("AAA.02.001", "Вал", "PART", 2.0),
                ItemDto("AAA.02.002", "Втулка", "PART", 4.0),
                ItemDto(null, "Болт М6 ГОСТ 7798-70", "STANDARD", 8.0)
            )
        )
    )

    @Test
    fun `full pipeline renders specification to SVG matching golden snapshot and to a valid PDF`() {
        val data = fixtureData()
        // Real A4 + the builder's own default ESKD margins — the columns (10+35+80+15=140mm,
        // matching architecture-0.1.md §4) are sized for that, not for an arbitrary tiny page.
        val doc = specification(data)

        val registry = FontRegistry()
        val fontRef = registry.register("pt-sans", loadFontBytes())
        val textMeasurer = PdfBoxTextMeasurer(registry) { fontRef }

        val laidOut = layOut(doc, textMeasurer) { fontRef }
        val svg = render(laidOut).single()

        val golden = File("src/test/resources/snapshots/specification.svg")
        if (!golden.exists()) {
            golden.parentFile.mkdirs()
            golden.writeText(svg)
        }
        assertEquals(golden.readText(), svg)

        assertTrue(svg.contains("Корпус"))
        assertTrue(svg.contains("Вал"))
        assertTrue(svg.contains("Втулка"))
        assertTrue(svg.contains("Болт М6"))

        val pdfBytes = renderToPdf(laidOut, registry)
        Loader.loadPDF(pdfBytes).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
        }
    }
}
