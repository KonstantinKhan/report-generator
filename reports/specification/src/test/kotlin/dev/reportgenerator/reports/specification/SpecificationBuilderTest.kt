package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecificationBuilderTest {

    @Test
    fun `specification groups items by kind with continuous position numbering`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            items = listOf(
                SpecificationItem("AAA.01.000", "Корпус", ItemKind.ASSEMBLY, 1),
                SpecificationItem("AAA.02.001", "Вал", ItemKind.PART, 2),
                SpecificationItem("AAA.02.002", "Втулка", ItemKind.PART, 4),
                SpecificationItem("ГОСТ 7798-70", "Болт М6", ItemKind.STANDARD, 8)
            )
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val groups = table.content.filterIsInstance<IrGroup>()

        assertEquals(3, groups.size)
        assertEquals(1, groups[0].rows.size)
        assertEquals(2, groups[1].rows.size)
        assertEquals(1, groups[2].rows.size)

        val positions = groups.flatMap { it.rows }.map { it.cells.first().text }
        assertEquals(listOf("1", "2", "3", "4"), positions)
    }
}
