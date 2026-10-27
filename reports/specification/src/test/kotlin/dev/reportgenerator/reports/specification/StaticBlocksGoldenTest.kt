package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FrameBindings
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation as IrTextOrientation
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

// Golden guard for static blocks (stamp, left margin, below-frame notes, ...): a deterministic text
// dump of the whole LaidOutDocument (every coordinate as raw 1/100 mm) must not change when the
// way those blocks are described/anchored changes. A missing golden file is generated on first run.
// GOLDEN_PDF_DIR=<dir> additionally writes the rendered PDFs there (reference only, not compared).
class StaticBlocksGoldenTest {

    private val fonts = DefaultFontRegistry.load()
    private val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    private fun data(items: Int) = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        parts = listOf(
            PartItem(position = 2, designation = "AAA.02.001", name = "Вал", quantity = 2.0),
            PartItem(position = 3, designation = "AAA.02.003", name = "Втулка распределительная консольная весовая", quantity = 5.0),
        ),
        standards = List(items) { StandardItem(position = 4 + it, name = "Болт М6 ГОСТ 7798-70", quantity = 8.0) },
        materials = listOf(MaterialItem(position = 4 + items, name = "Сталь 45", quantity = 0.35, unit = "кг")),
    )

    // 1 assembly + 13 parts + 3 standard bolts: on A4 the first page holds exactly 26 flow rows, so
    // "Стандартные изделия" (2 blanks, title, blank) ends flush with the page bottom and its first
    // bolt does not fit. Before the orphan fix the title stranded there; now it moves to page 2.
    private fun groupBoundaryData() = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        parts = List(13) { PartItem(position = 2 + it, designation = "AAA.02.${100 + it}", name = "Вал $it", quantity = 2.0) },
        standards = List(3) { StandardItem(position = 15 + it, name = "Болт М6 ГОСТ 7798-70", quantity = 8.0) },
    )

    // All five sections; a designation too long for its 70mm column (hard-broken by characters), a name that
    // word-wraps, a material with its unit (shown in the note column).
    private fun allKindsData() = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Тестовое изделие",
        assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        parts = listOf(
            PartItem(position = 2, designation = "AAA.01.001-ОченьДлиннаяНеделимаяСтрокаОбозначенияБезПробеловИРазделителей", name = "Вал", quantity = 2.0),
            PartItem(position = 3, designation = "AAA.02.002", name = "Втулка распределительная  консольная весовая сборная", quantity = 5.0),
        ),
        standards = listOf(StandardItem(position = 4, name = "Болт М6 ГОСТ 7798-70", quantity = 8.0)),
        others = listOf(OtherItem(position = 5, name = "Пломба", quantity = 1.0)),
        materials = listOf(MaterialItem(position = 6, name = "Сталь 45", quantity = 0.35, unit = "кг")),
    )

    private fun customStamp(width: Int) = FrameSpec(
        Size(width.mm, 10.mm),
        listOf(
            FrameCell.Dynamic(Rect(0.mm, 0.mm, width.mm, 5.mm), "doc.designation", align = TextAlign.CENTER),
            FrameCell.Dynamic(Rect(0.mm, 5.mm, (width / 2).mm, 5.mm), "page.number"),
            FrameCell.Dynamic(Rect((width / 2).mm, 5.mm, (width / 2).mm, 5.mm), "page.total")
        )
    )

    private fun customSide() = FrameSpec(
        Size(8.mm, 50.mm),
        listOf(FrameCell.Constant(Rect(0.mm, 0.mm, 8.mm, 50.mm), "Сбоку", orientation = IrTextOrientation.VERTICAL_BOTTOM_TO_TOP))
    )

    private fun fixtures(): Map<String, IrDocument> {
        val a3Landscape = PageFormat("A3L", 420.mm, 297.mm)
        val single = specification(data(0))
        val multi = specification(data(17))
        val customBlocks = specification(data(17)).let {
            it.copy(
                pageSetup = PageSetup(
                    it.pageSetup.format, it.pageSetup.margins,
                    frame = customStamp(60), dataContext = FrameBindings("XXX.1", "Имя"),
                    continuationFrame = customStamp(40), leftMarginFrame = customSide(), belowFrame = FrameSpec(Size(30.mm, 5.mm), listOf(FrameCell.Constant(Rect(0.mm, 0.mm, 30.mm, 5.mm), "Нижняя")))
                )
            )
        }
        return linkedMapOf(
            "spec-single-page" to single,
            "spec-single-page-no-pz" to specification(data(0), customerRepresentative = false),
            "spec-multi-page" to multi,
            "spec-multi-page-no-pz" to specification(data(17), customerRepresentative = false),
            "spec-a3-landscape" to multi.copy(pageSetup = multi.pageSetup.copy(format = a3Landscape)),
            "spec-custom-blocks" to customBlocks,
            "spec-group-boundary" to specification(groupBoundaryData()),
            "spec-all-kinds-long-designation" to specification(allKindsData()),
            "no-table-frame-only" to IrDocument(single.pageSetup, single.elements.filter { it !is dev.reportgenerator.ir.IrTable })
        )
    }

    private fun dump(doc: LaidOutDocument): String = buildString {
        fun L(l: Length) = l.raw.toString()
        fun R(r: Rect) = "${L(r.x)} ${L(r.y)} ${L(r.width)} ${L(r.height)}"
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
    fun `layout of static blocks matches golden dumps`() {
        val pdfDir = System.getenv("GOLDEN_PDF_DIR")?.let(::File)?.also { it.mkdirs() }
        for ((name, doc) in fixtures()) {
            val laidOut = layOut(doc, measurer, fonts::resolve)
            val actual = dump(laidOut)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            assertEquals(golden.readText(), actual, "golden mismatch: $name")
            pdfDir?.let { File(it, "$name.pdf").writeBytes(renderToPdf(laidOut, fonts.registry)) }
        }
    }
}
