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

// The shape of the "Переменные данные для исполнений" part in the table content: 3 blank rows before the title, the
// title over the designation and name columns, then per block a blank, the label (designation of the execution), a blank,
// the groups of the block and 2 blank rows after it. Between two blocks there are 2 + 1 = 3 blank rows.
// The main part of the data here is one group (the part "Вал"), so the title is the element 4.
class VariantsSectionTest : ShouldSpec({

    fun data(vararg variants: SpecificationVariant) = SpecificationData(
        documentDesignation = "AAA.00.000",
        documentName = "Изделие",
        parts = listOf(PartItem(position = 1, designation = "AAA.02.001", name = "Вал", quantity = 2.0)),
        variants = variants.toList(),
    )

    fun block(designation: String, part: String) = SpecificationVariant(
        designation = designation,
        parts = listOf(PartItem(position = 1, designation = "AAA.03.$part", name = part, quantity = 1.0)),
    )

    fun content(data: SpecificationData): List<IrTableElement> =
        specification(data).elements.filterIsInstance<IrTable>().single().content

    fun IrTableElement.isBlank() = this is IrRow && cells.all { it.text.isEmpty() }

    should("have no variant part when no execution has a block") {
        content(data()).filterIsInstance<IrSpanRow>().shouldBeEmpty()
    }

    should("put 3 blank rows before the title, which spans the designation and the name columns") {
        val elements = content(data(block("AAA.00.000", "B1")))
        val title = 4
        val span = elements[title].shouldBeInstanceOf<IrSpanRow>()

        span.from shouldBe "designation"
        span.to shouldBe "name"
        span.cell.text shouldBe VARIANTS_TITLE
        elements[0].shouldBeInstanceOf<IrGroup>()
        (1..3).all { elements[title - it].isBlank() } shouldBe true
    }

    should("put a blank, the label of the execution in the name column and a blank before its groups") {
        val elements = content(data(block("AAA.00.000", "B1")))
        val title = 4

        elements[title + 1].isBlank() shouldBe true
        val label = elements[title + 2].shouldBeInstanceOf<IrRow>()
        label.cells[NAME_COLUMN].text shouldBe "AAA.00.000"
        label.cells.filterIndexed { index, _ -> index != NAME_COLUMN }.all { it.text.isEmpty() } shouldBe true
        elements[title + 3].isBlank() shouldBe true
        elements[title + 4].shouldBeInstanceOf<IrGroup>().title shouldBe "Детали"
    }

    should("put 3 blank rows between two blocks: 2 after the previous block and 1 before the next label") {
        val elements = content(data(block("AAA.00.000", "B1"), block("AAA.00.000-02", "B2")))
        val secondLabel = elements.indexOfFirst { it is IrRow && it.cells[NAME_COLUMN].text == "AAA.00.000-02" }

        var blanks = 0
        var index = secondLabel - 1
        while (elements[index].isBlank()) {
            blanks += 1
            index -= 1
        }
        blanks shouldBe 3
        elements[index].shouldBeInstanceOf<IrGroup>()
    }
}) {
    private companion object {
        // The name column is the 5th of the sheet (format, zone, position, designation, name, quantity, note).
        const val NAME_COLUMN = 4
    }
}
