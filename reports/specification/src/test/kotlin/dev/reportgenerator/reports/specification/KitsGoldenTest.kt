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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Golden guard for the kit blocks (комплекты без назначения и без Спецификации): a deterministic text dump of the laid out
// sheet with the blocks in the main part and in the block of an execution. A missing golden file is generated on first run.
class KitsGoldenTest {

    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    // Main part: one common part and two kit blocks (the kit with a specification row is not here). The first block has
    // a part and a standard, the second a part only.
    private fun mainData() = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        parts = listOf(PartItem(position = 1, designation = "AAA.02.001", name = "Вал", quantity = 2.0)),
        kitBlocks = listOf(
            KitBlock(
                label = "Комплект крепления",
                parts = listOf(PartItem(position = 1, designation = "AAA.05.001", name = "Винт", quantity = 1.0)),
                standards = listOf(StandardItem(position = 2, name = "Шайба 6 ГОСТ 11371-78", quantity = 2.0)),
            ),
            KitBlock(
                label = "Комплект уплотнения",
                parts = listOf(PartItem(designation = "AAA.06.001", name = "Прокладка", quantity = 1.0)),
            ),
        ),
    )

    // The execution has its own kit block: the kit rows are empty there, so the title of the section is drawn in the block.
    private fun variantData() = mainData().copy(
        kitBlocks = emptyList(),
        variants = listOf(
            SpecificationVariant(
                designation = "AAA.00.000-02",
                parts = listOf(PartItem(position = 2, designation = "AAA.02.003", name = "Крышка", quantity = 1.0)),
                kitBlocks = listOf(
                    KitBlock(
                        label = "Комплект исполнения",
                        parts = listOf(PartItem(position = 1, designation = "AAA.07.001", name = "Болт", quantity = 4.0)),
                    ),
                ),
            ),
        ),
    )

    private fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "spec-kit-blocks-main" to specification(mainData()),
        "spec-kit-blocks-variant" to specification(variantData()),
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
    fun `kit blocks match golden dumps`() {
        for ((name, doc) in fixtures()) {
            val actual = dump(layOut(doc, measurer, fonts::resolve))
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
        }
    }
}
