package dev.reportgenerator.reports.specification

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import dev.reportgenerator.domain.CompositionUsage
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Quantity
import dev.reportgenerator.domain.Usage
import dev.reportgenerator.domain.VariantUsage
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.geometry.PageFormat
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

// The blocks of the group specification: ten execution columns per flow table, numbered as the executions
// ("-" for the main part, 01..09, 10, 11, ...), every block with the full composition.
class GroupSpecificationBlocksTest : ShouldSpec({

    var nextId = 500
    fun part(designation: String) = PdmNode(
        id = PdmNodeId(nextId++),
        kind = PdmNodeKind.Part,
        attributes = mapOf(
            AttributeKey.Designation to AttributeValue.Text(designation),
            AttributeKey.Name to AttributeValue.Text("Деталь $designation"),
        ),
    )

    fun link(target: PdmNode) = CompositionUsage(
        target = target,
        position = Field.Missing,
        quantity = Quantity.Exact(1.0),
        unit = Field.Missing,
    )

    // `count` variants (the designations ИЗД-1..ИЗД-count) and the root with the parts A and B; the variant k has the part A
    // only for even k, so the rows differ between the blocks.
    fun product(count: Int): PdmNode {
        val a = part("АБ.01")
        val b = part("АБ.02")
        val variants = (1..count).map { k ->
            val usages: List<Usage> = if (k % 2 == 0) listOf(link(a)) else listOf(link(b))
            PdmNode(
                id = PdmNodeId(nextId++),
                kind = PdmNodeKind.Assembly,
                attributes = mapOf(
                    AttributeKey.Designation to AttributeValue.Text("ИЗД-$k"),
                    AttributeKey.Name to AttributeValue.Text("Исполнение $k"),
                ),
                usages = usages,
            )
        }
        return PdmNode(
            id = PdmNodeId(1),
            kind = PdmNodeKind.Assembly,
            attributes = mapOf(
                AttributeKey.Designation to AttributeValue.Text("ИЗД.00"),
                AttributeKey.Name to AttributeValue.Text("Изделие"),
            ),
            usages = listOf(link(a), link(b)) + variants.map { VariantUsage(target = it) },
        )
    }

    // The header texts of the second level (the execution numbers) of a flow table.
    fun numbersOf(table: IrTable): List<String> = table.header!!.grid!!.cells
        .filter { it.row == 1 }
        .sortedBy { it.col }
        .map { it.cell.text }

    fun topOf(table: IrTable): String = table.header!!.grid!!.cells.single { it.row == 0 && it.span == 10 }.cell.text

    fun tables(document: IrDocument): List<IrTable> =
        document.elements.filterIsInstance<IrTable>()

    should("number the execution columns: the main part as a dash, then 01..09 and 10, 11 without a leading zero") {
        listOf(0, 1, 9, 10, 11, 19, 20).map { executionLabel(it) } shouldBe
            listOf("-", "01", "09", "10", "11", "19", "20")
    }

    should("draw one flow table with the main part only for a product without executions") {
        val document = groupSpecification(product(0).toGroupSpecificationData())

        tables(document).size shouldBe 1
        numbersOf(tables(document).single()) shouldBe listOf("-") + List(9) { "" }
    }

    should("draw one flow table for up to ten execution columns, the main part first") {
        val document = groupSpecification(product(9).toGroupSpecificationData())

        tables(document).size shouldBe 1
        numbersOf(tables(document).single()) shouldBe listOf("-", "01", "02", "03", "04", "05", "06", "07", "08", "09")
    }

    should("cut the executions into blocks of ten columns, the second block numbered from 10") {
        val document = groupSpecification(product(11).toGroupSpecificationData())
        val blocks = tables(document)

        blocks.size shouldBe 2
        numbersOf(blocks[0]) shouldBe listOf("-", "01", "02", "03", "04", "05", "06", "07", "08", "09")
        numbersOf(blocks[1]) shouldBe listOf("10", "11") + List(8) { "" }
    }

    should("write the designation of the root and the mark of the main part in the top header cell") {
        val document = groupSpecification(product(11).toGroupSpecificationData())

        topOf(tables(document)[0]) shouldBe "Кол. на исполнение   ИЗД.00-"
        topOf(tables(document)[1]) shouldBe "Кол. на исполнение   ИЗД.00-"
    }

    should("repeat the full composition in every block") {
        val document = groupSpecification(product(11).toGroupSpecificationData())
        val rowCounts = tables(document).map { table ->
            table.content.filterIsInstance<IrGroup>().sumOf { it.rows.size }
        }

        rowCounts[0] shouldBe rowCounts[1]
        rowCounts[0] shouldBe 2
    }

    should("use the landscape A4 sheet") {
        val document = groupSpecification(product(0).toGroupSpecificationData())

        document.pageSetup.format shouldBe PageFormat.A4_LANDSCAPE
    }
})
