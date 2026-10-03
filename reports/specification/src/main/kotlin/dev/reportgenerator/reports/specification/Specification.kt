package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.ir.FlowGroup
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.FrameSpecs
import dev.reportgenerator.ir.frames.GostSpecTemplate

// The main table is described by the `body` flow block of gost-spec.yaml (columns, header, row cells,
// spacers, styles). Here the code supplies the data: the groups (titles, order, kind filter), the position
// numbering and the rows' `item` records. Stage 3 moves groupBy / sortBy / sequence / format to YAML.
fun specification(data: SpecificationData, customerRepresentative: Boolean = true): IrDocument {
    var nextPosition = 0

    // ГОСТ Р 2.106-2019 §1 order; a kind without items gets no group (no title, no spacers).
    val groups = GROUPS.mapNotNull { (title, kind) ->
        data.items.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { items ->
            FlowGroup(title, items.map { itemRecord(it, ++nextPosition) })
        }
    }
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

        table(FlowTables.build(GostSpecTemplate.flowTable, dataContext.schema, groups, GostSpecTemplate.flowTablePath))
    }
}

private val GROUPS = listOf(
    "Сборочные единицы" to ItemKind.ASSEMBLY,
    "Детали" to ItemKind.PART,
    "Стандартные изделия" to ItemKind.STANDARD,
    "Прочие изделия" to ItemKind.OTHER,
    "Материалы" to ItemKind.MATERIAL
)
