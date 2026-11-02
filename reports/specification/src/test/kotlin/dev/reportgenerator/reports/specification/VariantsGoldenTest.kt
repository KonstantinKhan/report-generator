package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.renderpdf.renderToPdf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Golden guard for the "Переменные данные для исполнений" part: a deterministic text dump of the laid out sheet with a
// main part and variant blocks, on one page and on several. A missing golden file is generated on first run.
// GOLDEN_PDF_DIR=<dir> additionally writes the rendered PDFs there (reference only, not compared).
class VariantsGoldenTest {

    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    // Main part: one common part. Root block: one unique part and one standard. Variant block: one unique part and
    // one part with a quantity range (the note of the range goes to the notes).
    private fun singlePageData() = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        parts = listOf(PartItem(position = 1, designation = "AAA.02.001", name = "Вал", quantity = 2.0)),
        variants = listOf(
            SpecificationVariant(
                designation = "AAA.00.000",
                parts = listOf(PartItem(position = 2, designation = "AAA.02.002", name = "Втулка", quantity = 1.0)),
                standards = listOf(StandardItem(position = 3, name = "Болт М6 ГОСТ 7798-70", quantity = 4.0)),
            ),
            SpecificationVariant(
                designation = "AAA.00.000-02",
                parts = listOf(PartItem(position = 2, designation = "AAA.02.003", name = "Крышка", quantity = 1.0, notes = listOf("Наиб. кол. 3"))),
            ),
        ),
    )

    // Many unique standards in every execution: the variant blocks run over several pages.
    private fun multiPageData() = singlePageData().copy(
        variants = listOf(
            SpecificationVariant(
                designation = "AAA.00.000",
                standards = List(20) { StandardItem(position = it + 1, name = "Болт М${it + 1} ГОСТ 7798-70", quantity = 8.0) },
            ),
            SpecificationVariant(
                designation = "AAA.00.000-02",
                standards = List(20) { StandardItem(position = it + 1, name = "Гайка М${it + 1} ГОСТ 5915-70", quantity = 4.0) },
            ),
        ),
    )

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "spec-variants-single-page" to specification(singlePageData()),
        "spec-variants-multi-page" to specification(multiPageData()),
    )

    private fun dump(doc: LaidOutDocument): String = buildString {
        fun L(l: Length) = l.raw.toString()
        fun R(r: dev.reportgenerator.geometry.Rect) = "${L(r.x)} ${L(r.y)} ${L(r.width)} ${L(r.height)}"
        for (page in doc.pages) {
            appendLine("PAGE ${page.number} ${page.format.name} ${L(page.format.width)} ${L(page.format.height)}")
            for (e in page.elements) when (e) {
                is PositionedText -> appendLine("T \"${e.text}\" ${R(e.rect)} ${e.style.font.id} ${e.style.sizePt} i=${e.style.italic} ${e.orientation}")
                is Line -> appendLine("L ${L(e.from.x)} ${L(e.from.y)} ${L(e.to.x)} ${L(e.to.y)} w=${L(e.style.width)} ${e.style.color}")
                is Rectangle -> appendLine("R ${R(e.rect)} w=${L(e.style.width)} ${e.style.color}")
                is PositionedImage -> appendLine("I ${R(e.rect)} ${e.image.id}")
            }
        }
    }

    @Test
    fun `variant blocks match golden dumps`() {
        val pdfDir = System.getenv("GOLDEN_PDF_DIR")?.let(::File)?.also { it.mkdirs() }
        for ((name, doc) in fixtures()) {
            val laidOut = layOut(doc, measurer, fonts::resolve)
            val actual = dump(laidOut)
            pdfDir?.let { File(it, "$name.pdf").writeBytes(renderToPdf(laidOut, fonts.registry)) }
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }
}
