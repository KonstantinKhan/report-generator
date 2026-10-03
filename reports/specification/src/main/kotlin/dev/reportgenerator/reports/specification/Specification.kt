package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.FrameSpecs
import dev.reportgenerator.ir.frames.GostSpecTemplate

// The main table is described by the `body` flow block of gost-spec.yaml: columns, header, group order and
// titles (ГОСТ Р 2.106-2019 §1), position numbering, how the quantity and the unit are shown. Here the code
// only supplies the data: the schema and one typed record per item.
fun specification(data: SpecificationData, customerRepresentative: Boolean = true): IrDocument {
    val dataContext = data.toDataContext()

    return document {
        title(data.documentName)

        pageSetup(
            frame = FrameSpecs.firstPageStamp,
            dataContext = dataContext,
            continuationFrame = FrameSpecs.continuationPageStamp,
            leftMarginFrame = FrameSpecs.leftMarginTable,
            specLeftTable = FrameSpecs.specLeftTable,
            mainTitleRightTable = if (customerRepresentative) FrameSpecs.mainTitleRightTable else null,
            belowFrame = FrameSpecs.belowFrameNotes
        )

        table(FlowTables.build(GostSpecTemplate.flowTable, dataContext.schema, data.itemRecords(), GostSpecTemplate.flowTablePath))
    }
}
