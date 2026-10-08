package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.frames.GostSpecTemplate
import dev.reportgenerator.template.FlowStick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The IrTable built from the `body` flow block of gost-spec.yaml must equal the one the old hardcoded
// builder produced (LegacySpecificationTable), field for field.
class SpecificationTableParityTest {
    private fun data(items: List<SpecificationItem>) = SpecificationData("AAA.00.000", "Тестовое изделие", items)

    private fun table(data: SpecificationData): IrTable = specification(data).elements.filterIsInstance<IrTable>().single()

    private val allKinds = listOf(
        SpecificationItem("AAA.01.000", "Корпус", ItemKind.ASSEMBLY, 1.0),
        SpecificationItem("AAA.01.001-ОченьДлиннаяНеделимаяСтрокаОбозначенияБезПробеловИРазделителей", "Вал", ItemKind.PART, 2.0),
        SpecificationItem("AAA.02.002", "Втулка распределительная  консольная весовая сборная", ItemKind.PART, 5.0),
        SpecificationItem(null, "Болт М6 ГОСТ 7798-70", ItemKind.STANDARD, 8.0, "шт"),
        SpecificationItem("", "Пломба", ItemKind.OTHER, 1.0),
        SpecificationItem(null, "Сталь 45", ItemKind.MATERIAL, 0.35, "кг"),
        SpecificationItem(null, "Лента", ItemKind.MATERIAL, 12.0)
    )

    @Test
    fun `all kinds equal the legacy table`() = assertEquals(LegacySpecificationTable.table(data(allKinds)), table(data(allKinds)))

    @Test
    fun `items in a shuffled source order equal the legacy table (kinds regroup, source order kept inside)`() {
        val shuffled = allKinds.reversed() + allKinds.take(3)
        assertEquals(LegacySpecificationTable.table(data(shuffled)), table(data(shuffled)))
    }

    @Test
    fun `empty specification equals the legacy table`() = assertEquals(LegacySpecificationTable.table(data(emptyList())), table(data(emptyList())))

    @Test
    fun `groups with a single kind and missing kinds equal the legacy table`() {
        val d = data(allKinds.filter { it.kind == ItemKind.STANDARD || it.kind == ItemKind.MATERIAL })
        assertEquals(LegacySpecificationTable.table(d), table(d))
        assertEquals(listOf("Стандартные изделия", "Материалы"), table(d).content.filterIsInstance<IrGroup>().map { it.title })
    }

    @Test
    fun `stick of the yaml columns maps to stickToFirstRow and stickToLastRow`() {
        val columns = table(data(emptyList())).columns
        assertEquals(listOf("format", "zone", "position"), columns.filter { it.stickToFirstRow }.map { it.id })
        assertEquals(listOf("quantity", "note"), columns.filter { it.stickToLastRow }.map { it.id })
        assertEquals(
            listOf(FlowStick.FIRST, FlowStick.FIRST, FlowStick.FIRST, FlowStick.NONE, FlowStick.NONE, FlowStick.LAST, FlowStick.LAST),
            GostSpecTemplate.flowTable.columns.map { it.stick }
        )
    }

    @Test
    fun `header repeats and the flow columns fill the sheet content width`() {
        val t = table(data(emptyList()))
        assertTrue(requireNotNull(t.header).repeat)
        assertEquals(185.0, GostSpecTemplate.flowTable.columns.sumOf { it.width })
    }
}
