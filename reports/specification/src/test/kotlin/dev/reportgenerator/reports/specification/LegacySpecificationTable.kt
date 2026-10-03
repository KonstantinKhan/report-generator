package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.GroupBuilder
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.TableBuilder
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation
import dev.reportgenerator.ir.document
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

// The specification table as it was built before it moved into gost-spec.yaml (all constants hardcoded:
// widths, header texts and styles, rowHeight 8, group title column). Kept only for SpecificationTableParityTest;
// IrGroupTitle / fillBlank / header.repeat are the IR defaults, which were the engine's fixed behaviour then
// (2 blanks before, 1 after, fill with blank rows, header on every page). IrColumn.header no longer exists
// (the header text lives in IrTableHeader.cells only).
object LegacySpecificationTable {
    fun table(data: SpecificationData): IrTable {
        var nextPosition = 0
        return document {
            table(rowHeight = 8.mm, groupTitleColumn = "name") {
                columns {
                    column("format", 6.mm, stickToFirstRow = true)
                    column("zone", 6.mm, stickToFirstRow = true)
                    column("position", 8.mm, stickToFirstRow = true)
                    column("designation", 70.mm)
                    column("name", 63.mm)
                    column("quantity", 10.mm, stickToLastRow = true)
                    column("note", 22.mm, stickToLastRow = true)
                }

                header(height = 15.mm) {
                    cell("Формат", orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP)
                    cell("Зона", orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP)
                    cell("Поз.", orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP)
                    cell("Обозначение")
                    cell("Наименование")
                    cell("Кол.", orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP)
                    cell("Примечание", manualLines = listOf("Приме-", "чание"))
                }

                listOf(
                    "Сборочные единицы" to ItemKind.ASSEMBLY,
                    "Детали" to ItemKind.PART,
                    "Стандартные изделия" to ItemKind.STANDARD,
                    "Прочие изделия" to ItemKind.OTHER,
                    "Материалы" to ItemKind.MATERIAL
                ).forEach { (title, kind) ->
                    groupIfNotEmpty(title, data.items.filter { it.kind == kind }) { item ->
                        nextPosition += 1
                        row(item, nextPosition)
                    }
                }
            }
        }.elements.filterIsInstance<IrTable>().single()
    }

    private fun TableBuilder.groupIfNotEmpty(title: String, items: List<SpecificationItem>, block: GroupBuilder.(SpecificationItem) -> Unit) {
        if (items.isEmpty()) return
        group(title) { items.forEach { item -> block(item) } }
    }

    private fun GroupBuilder.row(item: SpecificationItem, position: Int) {
        row(
            listOf(
                IrCell("", align = TextAlign.CENTER),
                IrCell("", align = TextAlign.CENTER),
                IrCell(position.toString(), align = TextAlign.CENTER),
                IrCell(item.designation ?: ""),
                IrCell(item.name),
                IrCell(item.legacyFormattedQuantity(), align = TextAlign.CENTER),
                IrCell(if (item.kind == ItemKind.MATERIAL) item.unit ?: "" else "", align = TextAlign.CENTER)
            )
        )
    }
}

// The quantity text as SpecificationData.formattedQuantity() produced it before `format` moved into the YAML
// (the `quantity` cell of gost-spec.yaml). The oracle of SpecificationQuantityFormatTest.
// MATERIAL quantities can be fractional (up to 2 decimals); every other kind is always a whole count
// in Loodsman, so it's rounded and shown without a fractional part.
fun SpecificationItem.legacyFormattedQuantity(): String = when (kind) {
    ItemKind.MATERIAL -> legacyMaterialQuantity(quantity)
    else -> quantity.roundToLong().toString()
}

private fun legacyMaterialQuantity(value: Double): String {
    val plain = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    val trimmed = if (plain.contains('.')) plain.trimEnd('0').trimEnd('.') else plain
    return trimmed.replace('.', ',')
}
