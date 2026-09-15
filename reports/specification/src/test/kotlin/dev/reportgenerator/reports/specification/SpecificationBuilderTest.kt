package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.TextOrientation
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

        val positions = groups.flatMap { it.rows }.map { it.cells[2].text }
        assertEquals(listOf("1", "2", "3", "4"), positions)
    }

    @Test
    fun `header has seven ESKD columns with correct orientation and manual note break`() {
        val data = SpecificationData(documentDesignation = "AAA.00.000", documentName = "Тест", items = emptyList())

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val header = requireNotNull(table.header)

        assertEquals(7, header.cells.size)
        assertEquals(15.mm, header.height)

        val vertical = listOf(0, 1, 2, 5)
        vertical.forEach { index ->
            assertEquals(
                TextOrientation.VERTICAL_BOTTOM_TO_TOP,
                header.cells[index].orientation,
                "column $index should be vertical"
            )
        }

        assertEquals("Примечание", header.cells[6].text)
        assertEquals(listOf("Приме-", "чание"), header.cells[6].manualLines)
    }
}
