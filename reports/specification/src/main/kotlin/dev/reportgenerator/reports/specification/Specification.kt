package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.ColumnsBuilder
import dev.reportgenerator.ir.GroupBuilder
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.document

fun specification(data: SpecificationData): IrDocument {
    var nextPosition = 0

    return document {
        title(data.documentName)

        table {
            columns {
                position(10.mm)
                designationColumn(35.mm)
                nameColumn(80.mm)
                quantityColumn(15.mm)
            }

            header("Поз.", "Обозначение", "Наименование", "Кол.")

            group("Сборочные единицы") {
                data.items.filter { it.kind == ItemKind.ASSEMBLY }.forEach { item ->
                    nextPosition += 1
                    row(item, nextPosition)
                }
            }

            group("Детали") {
                data.items.filter { it.kind == ItemKind.PART }.forEach { item ->
                    nextPosition += 1
                    row(item, nextPosition)
                }
            }

            group("Стандартные изделия") {
                data.items.filter { it.kind == ItemKind.STANDARD }.forEach { item ->
                    nextPosition += 1
                    row(item, nextPosition)
                }
            }
        }
    }
}

private fun ColumnsBuilder.position(width: Length) = column("position", width, header = "Поз.")
private fun ColumnsBuilder.designationColumn(width: Length) = column("designation", width, header = "Обозначение")
private fun ColumnsBuilder.nameColumn(width: Length) = column("name", width, header = "Наименование")
private fun ColumnsBuilder.quantityColumn(width: Length) = column("quantity", width, header = "Кол.")

private fun GroupBuilder.row(item: SpecificationItem, position: Int) {
    row(
        listOf(
            IrCell(position.toString()),
            IrCell(item.designation),
            IrCell(item.name),
            IrCell(item.quantity.toString())
        )
    )
}
