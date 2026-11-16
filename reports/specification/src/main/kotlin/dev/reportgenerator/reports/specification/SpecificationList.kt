package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.StaticSlot
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.GostSpecTemplate

// The specification list sheet ("Ведомость спецификаций", ВС): A3 landscape (420 x 297), the working area 395 x 287 with the
// left margin of 20 mm (binding strip), the rest 5 mm. The sheet template is resources/templates/gost-spec-list.yaml (its
// geometry is the same as the constants below). There are no left-margin and spec-left blocks on this sheet.
private val LIST_MARGINS = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 20.mm)

// A3 landscape: PageFormat.A3 is the portrait sheet (297 x 420) in report-geometry; the list is 420 x 297 (no such constant).
private val A3_LANDSCAPE = PageFormat("A3L", 420.mm, 297.mm)

// Specification list: one flow table of the whole list (the rows of the sheet, the sections, the parents in "Куда входит").
// The title blocks are the same as on the group sheet; the customer representative table is on when `customerRepresentative`.
fun specificationList(data: SpecificationListData, customerRepresentative: Boolean = true): IrDocument {
    val dataContext = data.toDataContext()
    val sheet = GostSpecTemplate.LIST_LANDSCAPE

    return document {
        title(data.documentName)

        pageSetup(
            format = A3_LANDSCAPE,
            margins = LIST_MARGINS,
            frame = sheet.frameSpec(StaticSlot.FRAME),
            dataContext = dataContext,
            continuationFrame = sheet.frameSpec(StaticSlot.CONTINUATION_FRAME),
            mainTitleRightTable = if (customerRepresentative) sheet.frameSpec(StaticSlot.MAIN_TITLE_RIGHT) else null,
            belowFrame = sheet.frameSpec(StaticSlot.BELOW_FRAME),
            staticTemplate = sheet.template,
        )

        table(FlowTables.build(sheet.flowTable, dataContext.schema, data.itemRecords(), sheet.flowTablePath))
    }
}
