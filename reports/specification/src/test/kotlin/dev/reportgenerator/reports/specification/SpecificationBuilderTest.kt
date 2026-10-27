package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecificationBuilderTest {

    @Test
    fun `specification groups items by section and takes the position from each item`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
            parts = listOf(
                PartItem(position = 2, designation = "AAA.02.001", name = "Вал", quantity = 2.0),
                PartItem(position = 3, designation = "AAA.02.002", name = "Втулка", quantity = 4.0),
            ),
            standards = listOf(StandardItem(position = 4, designation = "ГОСТ 7798-70", name = "Болт М6", quantity = 8.0)),
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
    fun `sections appear in the fixed ESKD order with the positions of their items, materials included`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
            parts = listOf(PartItem(position = 2, designation = "AAA.02.001", name = "Вал", quantity = 2.0)),
            standards = listOf(StandardItem(position = 3, designation = "ГОСТ 7798-70", name = "Болт М6", quantity = 8.0)),
            others = listOf(OtherItem(position = 4, designation = "AAA.03.000", name = "Пломба", quantity = 1.0)),
            materials = listOf(MaterialItem(position = 5, name = "Сталь 45", quantity = 1.0)),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val groups = table.content.filterIsInstance<IrGroup>()

        assertEquals(
            listOf("Сборочные единицы", "Детали", "Стандартные изделия", "Прочие изделия", "Материалы"),
            groups.map { it.title }
        )

        val positions = groups.flatMap { it.rows }.map { it.cells[2].text }
        assertEquals(listOf("1", "2", "3", "4", "5"), positions)
    }

    @Test
    fun `a section with no items is omitted entirely, not printed empty`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val groups = table.content.filterIsInstance<IrGroup>()

        assertEquals(listOf("Сборочные единицы"), groups.map { it.title })
    }

    @Test
    fun `an item without position leaves the position cell empty`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            parts = listOf(PartItem(position = null, designation = "AAA.02.001", name = "Вал", quantity = 1.0)),
        )

        val doc = specification(data)
        val row = doc.elements.filterIsInstance<IrTable>().single().content.filterIsInstance<IrGroup>().single().rows.single()

        assertEquals("", row.cells[2].text)
    }

    @Test
    fun `documentation has an empty position and an empty quantity`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            documentation = listOf(DocumentationItem(designation = "AAA.00.000 СБ", name = "Сборочный чертёж")),
        )

        val doc = specification(data)
        val row = doc.elements.filterIsInstance<IrTable>().single().content.filterIsInstance<IrGroup>().single().rows.single()

        assertEquals("", row.cells[2].text)
        assertEquals("", row.cells[5].text)
    }

    @Test
    fun `row cells use left align for designation and name, center for the rest`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            assemblies = listOf(AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0)),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val row = table.content.filterIsInstance<IrGroup>().single().rows.single()

        // format, zone, position, designation, name, quantity, note
        val expectedAligns = listOf(
            TextAlign.CENTER, TextAlign.CENTER, TextAlign.CENTER,
            TextAlign.LEFT, TextAlign.LEFT,
            TextAlign.CENTER, TextAlign.CENTER
        )
        assertEquals(expectedAligns, row.cells.map { it.align })
    }

    @Test
    fun `quantity cell shows whole numbers without decimals, materials with comma decimals`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            parts = listOf(PartItem(position = 1, designation = "AAA.02.001", name = "Вал", quantity = 1.0)),
            materials = listOf(MaterialItem(position = 2, name = "Сталь 45", quantity = 0.35, notes = listOf("кг"))),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val rows = table.content.filterIsInstance<IrGroup>().flatMap { it.rows }

        assertEquals(listOf("1", "0,35"), rows.map { it.cells[5].text })
    }

    @Test
    fun `material unit goes into the note column, blank for the other sections`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            parts = listOf(PartItem(position = 1, designation = "AAA.02.001", name = "Вал", quantity = 1.0)),
            materials = listOf(MaterialItem(position = 2, name = "Сталь 45", quantity = 0.35, notes = listOf("кг"))),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val rows = table.content.filterIsInstance<IrGroup>().flatMap { it.rows }

        assertEquals(listOf("", "кг"), rows.map { it.cells[6].text })
    }

    @Test
    fun `format goes into the format column and notes into the note column of assemblies and parts`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            assemblies = listOf(
                AssemblyItem(position = 1, designation = "AAA.01.000", name = "Корпус", quantity = 1.0, format = "*)", notes = listOf("*) А3х3")),
            ),
            parts = listOf(
                PartItem(position = 2, designation = "AAA.02.001", name = "Вал", quantity = 2.0, format = "A4", notes = listOf("Закалить")),
            ),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val rows = table.content.filterIsInstance<IrGroup>().flatMap { it.rows }

        assertEquals(listOf("*)", "A4"), rows.map { it.cells[0].text })
        assertEquals(listOf("*) А3х3", "Закалить"), rows.map { it.cells[6].text })
    }

    @Test
    fun `two notes of one item both reach the note cell`() {
        val data = SpecificationData(
            documentDesignation = "AAA.00.000",
            documentName = "Тестовое изделие",
            standards = listOf(StandardItem(position = 1, name = "Болт М6", quantity = 8.0, notes = listOf("Оцинковать", "Хромать"))),
        )

        val doc = specification(data)
        val table = doc.elements.filterIsInstance<IrTable>().single()
        val rows = table.content.filterIsInstance<IrGroup>().flatMap { it.rows }
        val note = rows.single().cells[6].text

        assertTrue(note.contains("Оцинковать") && note.contains("Хромать"), "note cell: $note")
    }

    @Test
    fun `header has seven ESKD columns with correct orientation and manual note break`() {
        val data = SpecificationData(documentDesignation = "AAA.00.000", documentName = "Тест")

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
