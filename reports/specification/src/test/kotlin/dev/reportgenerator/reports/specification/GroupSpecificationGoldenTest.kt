package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.DocumentUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.VariantUsage
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Line
import dev.reportgenerator.layoutir.PositionedImage
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.renderpdf.renderToPdf
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import java.io.File

// Golden guard for the group specification: a deterministic dump of the laid out sheet (header levels, the stamp, the
// execution columns of every block, the page breaks between the blocks). A missing golden file is generated on first run;
// GOLDEN_PDF_DIR=<dir> additionally writes the rendered PDFs there (reference only, not compared).
class GroupSpecificationGoldenTest : ShouldSpec({

    val fonts = DefaultFontRegistry.load()
    val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    var nextId = 900
    fun part(designation: String, name: String) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.Part,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text(name),
        ),
    )

    fun link(target: PdmNode, quantity: Double) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = Quantity.Exact(quantity),
        unit = Field.Missing,
    )

    // The root with a documentation row, a part in the main part and in some executions, and `count` executions.
    fun product(count: Int, parts: Int): PdmNode {
        val drawing = PdmNode(
            id = PdmNodeId(nextId++),
            kind = PdmNodeKind.AssemblyDrawing,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ГРП.00.001 СБ"),
                AttributeKey.Name to AttributeValue.Text("Сборочный чертёж"),
            ),
        )
        val common = List(parts) { part("ГРП.02.${100 + it}", "Деталь $it") }
        val variants = (1..count).map { k ->
            val usages: List<Usage> = listOf(link(common[k % parts], 1.0)) +
                if (k % 3 == 0) listOf(link(part("ГРП.03.${100 + k}", "Особая $k"), 2.0)) else emptyList()
            PdmNode(
                id = PdmNodeId(nextId++),
                kind = PdmNodeKind.Assembly,
                attributes = mapOf(
                    AttributeKey.Designation to AttributeValue.Text("ГРП.00.00$k"),
                    AttributeKey.Name to AttributeValue.Text("Исполнение $k"),
                ),
                usages = usages,
            )
        }
        return PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ГРП.00.000"),
                AttributeKey.Name to AttributeValue.Text("Групповое изделие"),
            ),
            usages = listOf(DocumentUsage(target = drawing)) + common.map { link(it, 1.0) } + variants.map { VariantUsage(target = it) },
        )
    }

    fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        "group-single-block" to groupSpecification(product(count = 4, parts = 3).toGroupSpecificationData()),
        "group-multi-block" to groupSpecification(product(count = 12, parts = 8).toGroupSpecificationData()),
    )

    fun dump(doc: LaidOutDocument): String = buildString {
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

    should("lay out every fixture to its golden dump") {
        val pdfDir = System.getenv("GOLDEN_PDF_DIR")?.let(::File)?.also { it.mkdirs() }
        for ((name, doc) in fixtures()) {
            val laidOut = layOut(doc, measurer, fonts::resolve)
            val actual = dump(laidOut)
            val golden = File("src/test/resources/golden/$name.txt")
            if (!golden.exists()) golden.writeText(actual)
            actual shouldBe golden.readText()
            pdfDir?.let { File(it, "$name.pdf").writeBytes(renderToPdf(laidOut, fonts.registry)) }
        }
    }
})
