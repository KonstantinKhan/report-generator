package dev.reportgenerator.ir

// Text rows after the whole flow table, the appendix included: `blankRows` blank bordered rows (no span), then every
// text in its own row spanning the columns `spanFrom`..`spanTo`. The texts follow each other without spacers; a text that
// does not fit the span wraps over its lines.
data class FlowNotes(
    val texts: List<String>,
    val spanFrom: String,
    val spanTo: String,
    val blankRows: Int = 2,
)
