package dev.reportgenerator.reports.specification

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FlowNotes
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.document
import dev.reportgenerator.ir.frames.FrameSpecs
import dev.reportgenerator.ir.frames.GostSpecTemplate
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext

// The group specification sheet: A4 landscape (297 x 210), the working area 287 x 185 under the top margin of 20 mm.
// The strips of the portrait sheet that hung on the left edge (specLeft, leftMargin) are in the top margin here; the
// stamp, the continuation stamp and the customer representative table stay in the bottom-right corner of the frame.
// The sheet template is resources/templates/gost-group-spec.yaml (its geometry is the same as the constants below).
private val GROUP_MARGINS = Insets(top = 20.mm, right = 5.mm, bottom = 5.mm, left = 5.mm)

// The execution columns of one flow table: the blocks of this many executions follow each other (block 1: the main part
// and the executions 01..09, block 2: 10..19, ...). Every block is a separate flow table with a full composition.
internal const val GROUP_COLUMNS_PER_BLOCK = 10

// The label of the main part (column 0) in the header; the executions are numbered by their column (01, 02, ..., 10, ...).
internal const val GROUP_MAIN_LABEL = "-"

// The title of the execution header: the text, then the designation of the root object and the mark of the main part.
private const val GROUP_HEADER_TITLE = "Кол. на исполнение"
private const val GROUP_HEADER_GAP = "   "

// Group specification: one flow table per block of the execution columns (see GROUP_COLUMNS_PER_BLOCK). The tables are
// drawn one after another, each from a new page; the static blocks (stamp, customer representative table) are the same
// on every page. The rows, the sections and their order are the specification's; the cells of the execution columns are
// texts built here ("х", "-", quantities).
fun groupSpecification(data: GroupSpecificationData, customerRepresentative: Boolean = true): IrDocument {
    val dataContext = data.toDataContext()
    val frames = FrameSpecs.GROUP

    return document {
        title(data.documentName)

        pageSetup(
            format = PageFormat.A4_LANDSCAPE,
            margins = GROUP_MARGINS,
            frame = frames.firstPageStamp,
            dataContext = dataContext,
            continuationFrame = frames.continuationPageStamp,
            leftMarginFrame = frames.leftMarginTable,
            specLeftTable = frames.specLeftTable,
            mainTitleRightTable = if (customerRepresentative) frames.mainTitleRightTable else null,
            belowFrame = frames.belowFrameNotes,
            staticTemplate = GostSpecTemplate.GROUP_LANDSCAPE.template,
        )

        groupTables(data, dataContext).forEach { table(it) }
    }
}

// The flow tables of the blocks: at least one, so the sheet has its header and the main part also without executions.
private fun groupTables(data: GroupSpecificationData, dataContext: DataContext): List<IrTable> {
    val blocks = maxOf(1, (data.executions.size + GROUP_COLUMNS_PER_BLOCK - 1) / GROUP_COLUMNS_PER_BLOCK)
    val spec = GostSpecTemplate.GROUP_LANDSCAPE.flowTable
    val path = GostSpecTemplate.GROUP_LANDSCAPE.flowTablePath
    val notes = data.kitNoteTail()
    return (0 until blocks).map { block ->
        val first = block * GROUP_COLUMNS_PER_BLOCK
        val records = data.rows.map { row ->
            val texts = (0 until GROUP_COLUMNS_PER_BLOCK).map { row.values.getOrElse(first + it) { "" } }
            row.record(texts)
        }
        val table = FlowTables.build(spec, dataContext.schema, records, path, notes = notes)
        table.withHeaderTexts(data.headerTexts(first, spec.columns.indexOfFirst { it.id == "c1" }))
    }
}

// The texts of the two header levels of one block: the top cell over the execution columns ("Кол. на исполнение" and the
// designation of the root with the mark of the main part) and the number of every execution column ("", when the block
// has no such execution). Keys: (row, column) of the header grid.
private fun GroupSpecificationData.headerTexts(first: Int, firstColumn: Int): Map<Pair<Int, Int>, String> {
    val top = mapOf((0 to firstColumn) to "$GROUP_HEADER_TITLE$GROUP_HEADER_GAP$documentDesignation-")
    val numbers = (0 until GROUP_COLUMNS_PER_BLOCK).associate { i ->
        val column = first + i
        (1 to firstColumn + i) to if (column < executions.size) executionLabel(column) else ""
    }
    return top + numbers
}

// "-" for the main part, "01".."09" for the executions 1..9, "10", "11", ... from ten on (the number of the execution column).
internal fun executionLabel(column: Int): String = if (column == 0) GROUP_MAIN_LABEL else "%02d".format(column)

// The header texts are set on the grid of the header built from the spec (its structure and styles are the spec's).
private fun IrTable.withHeaderTexts(texts: Map<Pair<Int, Int>, String>): IrTable {
    val header = header ?: return this
    val grid = header.grid ?: return this
    val cells = grid.cells.map { placed ->
        texts[placed.row to placed.col]?.let { placed.copy(cell = placed.cell.copy(text = it)) } ?: placed
    }
    return copy(header = header.copy(grid = grid.copy(cells = cells)))
}

// The notes of the kits with a purpose: the tail of every table, as in the specification.
private fun GroupSpecificationData.kitNoteTail(): FlowNotes? {
    if (kitNotes.isEmpty()) return null
    return FlowNotes(texts = kitNotes, spanFrom = "designation", spanTo = "name")
}

// The data context of the group sheet: the document fields of the stamp and the item schema of the flow table
// (the specification's item fields and the ten execution columns c1..c10).
internal fun GroupSpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
        // null = no value: the bind of the customer representative table is `optional`, its cell stays empty.
        string("customerIndex", customerIndex)
    }
    item { groupItemFields(null, List(GROUP_COLUMNS_PER_BLOCK) { "" }) }
}

private fun GroupRow.record(texts: List<String>): DataValue.Record {
    val row = item
    return dataContext { item { groupItemFields(row, texts) } }.get("item") as DataValue.Record
}

// The item fields of the specification plus the text of every execution column of the block (c1 = the first column).
private fun RecordBuilder.groupItemFields(row: ItemRow?, texts: List<String>) {
    itemFields(row)
    texts.forEachIndexed { index, text -> string("c${index + 1}", text) }
}
