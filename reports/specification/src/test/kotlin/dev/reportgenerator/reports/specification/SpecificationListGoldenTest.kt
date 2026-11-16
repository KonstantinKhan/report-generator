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

// Golden guard for the specification list sheet ("Ведомость спецификаций", A3 landscape): a single page and several pages
// (the frame of the pages 2+), each with and without the customer representative table («ПЗ» with the customer index).
// A missing golden file is generated on first run; GOLDEN_PDF_DIR=<dir> additionally writes the rendered PDFs there
// (reference only, not compared).
class SpecificationListGoldenTest : ShouldSpec({

    val fonts = DefaultFontRegistry.load()
    val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    var nextId = 2
    fun specification(designation: String) = PdmNode(
        id = PdmNodeId(500_000 + nextId++),
        kind = PdmNodeKind.Specification,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Спецификация $designation"),
        ),
    )

    fun unit(kind: PdmNodeKind, designation: String, usages: List<Usage> = emptyList()): PdmNode = PdmNode(
        id = PdmNodeId(nextId++),
        kind = kind,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Узел $designation"),
        ),
        usages = listOf(DocumentUsage(target = specification(designation))) + usages,
    )

    fun link(target: PdmNode, quantity: Double) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = Quantity.Exact(quantity),
        unit = Field.Missing,
    )

    // A product: `groups` assemblies under the root; `leaves` assemblies, each in two groups (two parent lines, quantities
    // 1 and 2); two kits in the first group. The rows of the sheet are the groups, the leaves and the kits.
    fun product(groups: Int, leaves: Int, customerIndex: String?): PdmNode {
        val leafNodes = (1..leaves).map { unit(PdmNodeKind.Assembly, "СБ.1%03d".format(it)) }
        val kits = listOf(unit(PdmNodeKind.Kit, "КМ.02"), unit(PdmNodeKind.Kit, "КМ.01"))
        val groupNodes = (0 until groups).map { g ->
            val leafLinks = leafNodes
                .filterIndexed { k, _ -> k % groups == g || (k + 1) % groups == g }
                .mapIndexed { i, leaf -> link(leaf, if (i % 2 == 0) 1.0 else 2.0) }
            val kitLinks = if (g == 0) kits.map { link(it, 1.0) } else emptyList()
            unit(PdmNodeKind.Assembly, "СБ.0%02d".format(g + 1), leafLinks + kitLinks)
        }
        return PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            attributes = buildMap {
                put(AttributeKey.Designation, AttributeValue.Text("ИЗД.001.000"))
                put(AttributeKey.Name, AttributeValue.Text("Изделие"))
                if (customerIndex != null) put(AttributeKey.CustomerIndex, AttributeValue.Text(customerIndex))
            },
            usages = listOf(DocumentUsage(target = specification("ИЗД.00"))) + groupNodes.map { link(it, 1.0) },
        )
    }

    fun fixtures(): Map<String, IrDocument> = linkedMapOf(
        // one page: a few rows, the parents on several lines, the customer index in the customer representative table
        "list-single-page-pz" to specificationList(product(groups = 3, leaves = 6, customerIndex = "ИНД-1").toSpecificationListData()),
        "list-single-page-no-pz" to specificationList(product(groups = 3, leaves = 6, customerIndex = null).toSpecificationListData(), customerRepresentative = false),
        // several pages: 80 rows (the rows of 8 mm, ~30 rows per page), the frame of the pages 2+
        "list-multi-page-pz" to specificationList(product(groups = 6, leaves = 80, customerIndex = "ИНД-1").toSpecificationListData()),
        "list-multi-page-no-pz" to specificationList(product(groups = 6, leaves = 80, customerIndex = null).toSpecificationListData(), customerRepresentative = false),
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

    should("put the rows of several pages on the pages 2+ with the continuation frame") {
        val laidOut = layOut(fixtures().getValue("list-multi-page-pz"), measurer, fonts::resolve)

        (laidOut.pages.size > 1) shouldBe true
    }
})
