package dev.reportgenerator.reports.specification

import dev.reportgenerator.ir.frames.GostSpecTemplate
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.template.lineNumberColumn
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

// The specification list sheet: the template is loaded (the A3 landscape sheet with its flow table), and the sheet is laid
// out with the parents of an object on its own lines.
class SpecificationListTest : ShouldSpec({

    val fonts = DefaultFontRegistry.load()
    val measurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    val data = SpecificationListData(
        documentDesignation = "ИЗД.00 ВС",
        documentName = "Ведомость спецификаций",
        rows = listOf(
            SpecificationListRow(
                section = SpecificationSection.ASSEMBLY,
                designation = "СБ.01",
                name = "Узел",
                parents = listOf(SpecificationListParent("ИЗД.00", 1.0), SpecificationListParent("СБ.02", 2.0)),
            ),
            SpecificationListRow(
                section = SpecificationSection.KIT,
                designation = "КМ.01",
                name = "Комплект",
                parents = emptyList(),
            ),
        ),
    )

    should("load the list template: its name and the 7 columns of the body") {
        GostSpecTemplate.LIST_LANDSCAPE.template.name shouldBe "gost-spec-list"
        GostSpecTemplate.LIST_LANDSCAPE.flowTable.columns.size shouldBe 7
    }

    should("lay out the list: every object and every parent is a text of the sheet") {
        val laidOut = layOut(specificationList(data), measurer, fonts::resolve)
        val texts = laidOut.pages.flatMap { page -> page.elements.filterIsInstance<PositionedText>().map { it.text } }

        texts.shouldNotBeEmpty()
        texts shouldContain "СБ.01"
        texts shouldContain "КМ.01"
        texts shouldContain "ИЗД.00"
        texts shouldContain "СБ.02"
    }

    should("number the physical lines of the list by the engine: the column is bound to \${line.number}") {
        GostSpecTemplate.LIST_LANDSCAPE.flowTable.lineNumberColumn() shouldBe "number"
    }

    should("lay out an empty list: the sheet without rows") {
        val empty = data.copy(rows = emptyList())
        val laidOut = layOut(specificationList(empty), measurer, fonts::resolve)

        laidOut.pages.size shouldBe 1
    }
})
