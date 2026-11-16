package dev.reportgenerator.reports.specification

import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.FrameSpecs
import dev.reportgenerator.ir.frames.GostSpecTemplate

// The main table is described by the `body` flow block of gost-spec.yaml: columns, header, group order and
// titles (ГОСТ Р 2.106-2019 §1), how the position, quantity and unit are shown. Here the code
// only supplies the data: the schema and one typed record per item.
fun specification(data: SpecificationData, customerRepresentative: Boolean = true): IrDocument {
    val dataContext = data.toDataContext()

    return document {
        title(data.documentName)

        pageSetup(
            frame = FrameSpecs.SPECIFICATION.firstPageStamp,
            dataContext = dataContext,
            continuationFrame = FrameSpecs.SPECIFICATION.continuationPageStamp,
            leftMarginFrame = FrameSpecs.SPECIFICATION.leftMarginTable,
            specLeftTable = FrameSpecs.SPECIFICATION.specLeftTable,
            mainTitleRightTable = if (customerRepresentative) FrameSpecs.SPECIFICATION.mainTitleRightTable else null,
            belowFrame = FrameSpecs.SPECIFICATION.belowFrameNotes
        )

        table(
            FlowTables.build(
                GostSpecTemplate.PORTRAIT.flowTable, dataContext.schema, data.itemRecords(), GostSpecTemplate.PORTRAIT.flowTablePath,
                appendix = data.variantAppendix(), notes = data.kitNoteTail(), kits = data.kitsFlow()
            )
        )
    }
}
