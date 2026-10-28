package dev.reportgenerator.ir

import dev.reportgenerator.template.DataValue

// An extra part of a flow table, drawn after its main groups in the same table (the same columns, spacers and styles):
// a title over the columns `titleFrom`..`titleTo`, then for every block a label in the column `labelColumn` and the
// groups of the block's rows (the groupBy of the table). Blank bordered rows are the spacers: `spacersBeforeTitle`
// before the title, `spacersBeforeLabel` between the title and a label, `spacersAfterBlock` after each block.
// The title, the label and the spacers between them stay with the first data line of the block (keep chain).
data class FlowAppendix(
    val title: String,
    val titleFrom: String,
    val titleTo: String,
    val labelColumn: String,
    val blocks: List<FlowAppendixBlock>,
    val spacersBeforeTitle: Int = 3,
    val spacersBeforeLabel: Int = 1,
    val spacersAfterBlock: Int = 2,
)

// `rows` are `item` records of the table's schema, grouped like the main rows.
data class FlowAppendixBlock(val label: String, val rows: List<DataValue.Record>)
