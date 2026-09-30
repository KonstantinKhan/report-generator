package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.data.formattedQuantity
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.ColumnsBuilder
import dev.reportgenerator.ir.FrameBindings
import dev.reportgenerator.ir.GroupBuilder
import dev.reportgenerator.ir.IrCell
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.TableBuilder
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.FrameSpecs

// Column widths (6+6+8+70+63+10+22=185mm) fill A4's content width exactly (210 - 20 left margin
// - 5 right margin), matching the ГОСТ 2.106 specification form header this table represents.
fun specification(data: SpecificationData): IrDocument {
    var nextPosition = 0

    return document {
        title(data.documentName)

        pageSetup(
            frame = FrameSpecs.firstPageStamp,
            frameBindings = FrameBindings(designation = data.documentDesignation, name = data.documentName),
            continuationFrame = FrameSpecs.continuationPageStamp,
            leftMarginFrame = FrameSpecs.leftMarginTable,
            specLeftTable = FrameSpecs.specLeftTable,
            mainTitleRightTable = FrameSpecs.mainTitleRightTable,
            belowFrame = FrameSpecs.belowFrameNotes
        )

        table(rowHeight = 8.mm, groupTitleColumn = "name") {
            columns {
                formatColumn(6.mm)
                zoneColumn(6.mm)
                position(8.mm)
                designationColumn(70.mm)
                nameColumn(63.mm)
                quantityColumn(10.mm)
                noteColumn(22.mm)
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

            groupIfNotEmpty("Сборочные единицы", data.items.filter { it.kind == ItemKind.ASSEMBLY }) { item ->
                nextPosition += 1
                row(item, nextPosition)
            }

            groupIfNotEmpty("Детали", data.items.filter { it.kind == ItemKind.PART }) { item ->
                nextPosition += 1
                row(item, nextPosition)
            }

            groupIfNotEmpty("Стандартные изделия", data.items.filter { it.kind == ItemKind.STANDARD }) { item ->
                nextPosition += 1
                row(item, nextPosition)
            }

            groupIfNotEmpty("Прочие изделия", data.items.filter { it.kind == ItemKind.OTHER }) { item ->
                nextPosition += 1
                row(item, nextPosition)
            }

            groupIfNotEmpty("Материалы", data.items.filter { it.kind == ItemKind.MATERIAL }) { item ->
                nextPosition += 1
                row(item, nextPosition)
            }
        }
    }
}

// Пустой блок (0 элементов данного вида) не должен появляться в документе вообще —
// ни заголовка, ни строк под ним.
private fun TableBuilder.groupIfNotEmpty(title: String, items: List<SpecificationItem>, block: GroupBuilder.(SpecificationItem) -> Unit) {
    if (items.isEmpty()) return
    group(title) {
        items.forEach { item -> block(item) }
    }
}

private fun ColumnsBuilder.formatColumn(width: Length) = column("format", width, header = "Формат", stickToFirstRow = true)
private fun ColumnsBuilder.zoneColumn(width: Length) = column("zone", width, header = "Зона", stickToFirstRow = true)
private fun ColumnsBuilder.position(width: Length) = column("position", width, header = "Поз.", stickToFirstRow = true)
private fun ColumnsBuilder.designationColumn(width: Length) = column("designation", width, header = "Обозначение")
private fun ColumnsBuilder.nameColumn(width: Length) = column("name", width, header = "Наименование")
private fun ColumnsBuilder.quantityColumn(width: Length) = column("quantity", width, header = "Кол.", stickToLastRow = true)
private fun ColumnsBuilder.noteColumn(width: Length) = column("note", width, header = "Примечание", stickToLastRow = true)

// Формат/Зона aren't modeled in SpecificationItem yet — left blank per row, matching column
// order (7 columns must line up between header and every data row). Примечание carries the
// MATERIAL unit (e.g. "кг", "м") — "Кол." itself is too narrow (10mm) to fit value + unit
// without wrapping (see TableMeasurement.splitRowIntoPhysicalRows history).
private fun GroupBuilder.row(item: SpecificationItem, position: Int) {
    row(
        listOf(
            IrCell("", align = TextAlign.CENTER),
            IrCell("", align = TextAlign.CENTER),
            IrCell(position.toString(), align = TextAlign.CENTER),
            IrCell(item.designation ?: ""),
            IrCell(item.name),
            IrCell(item.formattedQuantity(), align = TextAlign.CENTER),
            IrCell(item.unit ?: "", align = TextAlign.CENTER)
        )
    )
}
