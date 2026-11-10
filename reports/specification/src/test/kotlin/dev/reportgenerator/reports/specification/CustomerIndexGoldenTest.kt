package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
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
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import java.io.File

// Golden guard for the customer index («Индекс заказчика») in the bottom cell of the customer representative table
// (the main title block, 120 x 22). A missing golden file is generated on first run; the existing goldens do not change.
class CustomerIndexGoldenTest : ShouldSpec({

    val fonts = DefaultFontRegistry.load()
    val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    fun root(index: AttributeValue?): PdmNode {
        val part = PdmNode(
            id = PdmNodeId(2),
            kind = PdmNodeKind.Part,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ИЗД.002"),
                AttributeKey.Name to AttributeValue.Text("Деталь"),
            ),
        )
        return PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            attributes = buildMap {
                put(AttributeKey.Designation, AttributeValue.Text("ИЗД.001.000"))
                put(AttributeKey.Name, AttributeValue.Text("Изделие"))
                if (index != null) put(AttributeKey.CustomerIndex, index)
            },
            usages = listOf(
                CompositionUsage(target = part, position = Field.Missing, quantity = Quantity.Exact(2.0), unit = Field.Missing),
            ),
        )
    }

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

    fun laidOut(doc: IrDocument): String = dump(layOut(doc, measurer, fonts::resolve))

    fun checkGolden(name: String, actual: String) {
        val golden = File("src/test/resources/golden/$name.txt")
        if (!golden.exists()) golden.writeText(actual)
        actual shouldBe golden.readText()
    }

    should("show the index in the bottom cell of the specification's customer representative table") {
        val dump = laidOut(specification(root(AttributeValue.Text("ИНД-1")).toSpecificationData()))
        dump.lines().filter { it.startsWith("T \"ИНД-1\"") }.size shouldBe 1
        checkGolden("spec-customer-index", dump)
    }

    should("show the index in the bottom cell of the group specification's customer representative table") {
        val dump = laidOut(groupSpecification(root(AttributeValue.Text("ИНД-1")).toGroupSpecificationData()))
        dump.lines().filter { it.startsWith("T \"ИНД-1\"") }.size shouldBe 1
        checkGolden("group-customer-index", dump)
    }

    should("leave the bottom cell without text when the index is not loaded, empty or blank") {
        val baseline = laidOut(specification(root(null).toSpecificationData()))
        listOf(null, AttributeValue.Empty, AttributeValue.Text("  ")).forEach { index ->
            val dump = laidOut(specification(root(index).toSpecificationData()))
            dump shouldBe baseline
        }
        baseline.lines().none { it.startsWith("T \"") && it.contains("ИНД") } shouldBe true
    }

    should("leave the bottom cell without text in the group specification when the index is not loaded") {
        val baseline = laidOut(groupSpecification(root(null).toGroupSpecificationData()))
        laidOut(groupSpecification(root(AttributeValue.Empty).toGroupSpecificationData())) shouldBe baseline
        baseline.lines().none { it.startsWith("T \"") && it.contains("ИНД") } shouldBe true
    }

    should("not show the index when the sheet has no customer representative table") {
        val dump = laidOut(specification(root(AttributeValue.Text("ИНД-1")).toSpecificationData(), customerRepresentative = false))
        dump.lines().none { it.startsWith("T \"ИНД-1\"") } shouldBe true
    }

    should("keep the index text as the only added element of the sheet") {
        val without = laidOut(specification(root(null).toSpecificationData()))
        val with = laidOut(specification(root(AttributeValue.Text("ИНД-1")).toSpecificationData()))
        val added = with.lines().filterNot { it in without.lines() }
        added.size shouldBe 1
        added.single().startsWith("T \"ИНД-1\"") shouldBe true
        without.lines().filterNot { it in with.lines() } shouldBe emptyList()
    }
})
