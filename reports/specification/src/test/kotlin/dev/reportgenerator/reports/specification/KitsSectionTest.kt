package dev.reportgenerator.reports.specification

import dev.reportgenerator.ir.IrGroup
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrSpanRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.IrTableElement
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

// The kits section (after the materials) and the notes of the kits with a purpose: the notes follow the variants part,
// 2 blank rows (no span) before them, then one spanning row per note over the designation and name columns.
class KitsSectionTest : ShouldSpec({

    fun data(
        kits: List<KitItem> = emptyList(),
        kitNotes: List<String> = emptyList(),
        variants: List<SpecificationVariant> = emptyList(),
    ) = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Изделие",
        materials = listOf(MaterialItem(designation = null, name = "Сталь", quantity = 1.0)),
        kits = kits,
        variants = variants,
        kitNotes = kitNotes,
    )

    fun kit(designation: String) = KitItem(designation = designation, name = "Комплект", quantity = 1.0)

    fun block(designation: String) = SpecificationVariant(
        designation = designation,
        parts = listOf(PartItem(position = 1, designation = "AAA.03.B1", name = "Вал", quantity = 1.0)),
    )

    fun content(data: SpecificationData): List<IrTableElement> =
        specification(data).elements.filterIsInstance<IrTable>().single().content

    fun IrTableElement.isBlank() = this is IrRow && cells.all { it.text.isEmpty() }

    should("put the kits group after the materials group under the title Комплекты") {
        val groups = content(data(kits = listOf(kit("AAA.02.001")))).filterIsInstance<IrGroup>()

        groups.map { it.title } shouldBe listOf("Материалы", "Комплекты")
        groups.last().rows.single().cells.any { it.text == "AAA.02.001" } shouldBe true
    }

    should("have no span rows without notes and without variants") {
        content(data()).filterIsInstance<IrSpanRow>().shouldBeEmpty()
    }

    should("put 2 blank rows and then one spanning row per note after the main table, without variants") {
        val elements = content(data(kitNotes = listOf("Примечание - N1", "Примечание - N2")))
        val first = elements.size - 4

        elements[first].isBlank() shouldBe true
        elements[first + 1].isBlank() shouldBe true
        val note1 = elements[first + 2].shouldBeInstanceOf<IrSpanRow>()
        val note2 = elements[first + 3].shouldBeInstanceOf<IrSpanRow>()
        note1.cell.text shouldBe "Примечание - N1"
        note2.cell.text shouldBe "Примечание - N2"
        note1.from shouldBe "designation"
        note1.to shouldBe "name"
    }

    should("put the notes after the variants part, behind 2 blank rows of the appendix and 2 blank rows of the notes") {
        val elements = content(data(kitNotes = listOf("Примечание - N1"), variants = listOf(block("AAA.00.000"))))
        val noteIndex = elements.indexOfFirst { it is IrSpanRow && it.cell.text == "Примечание - N1" }

        noteIndex shouldBe elements.lastIndex
        (1..4).all { elements[noteIndex - it].isBlank() } shouldBe true
        elements[noteIndex - 5].shouldBeInstanceOf<IrGroup>()
    }
})
