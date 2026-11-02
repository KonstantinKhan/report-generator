package dev.reportgenerator.ir

import dev.reportgenerator.template.DataValue

// The kits blocks of a flow table, drawn after its main groups (the same columns, spacers and styles): for every block
// `spacersBeforeBlock` blank bordered rows, a label in the column `labelColumn` (the kit name), a blank row, then the
// groups of the block's rows (the groupBy of the table). The label, the blank row and the first data line stay together.
// The spacers, the label and the blank row below it stay with the first data line of the block (keep chain), so the
// title of the section is never left alone at the foot of a page.
// `title` = the section title, drawn in the group title style with its spacers before the blocks; set when the table
// has no kit rows of its own (the group of the kits is then skipped), null otherwise.
data class FlowKits(
    val blocks: List<FlowKitBlock>,
    val labelColumn: String,
    val title: String? = null,
    val spacersBeforeBlock: Int = 3,
)

// `rows` are `item` records of the table's schema, grouped like the main rows.
data class FlowKitBlock(val label: String, val rows: List<DataValue.Record>)
