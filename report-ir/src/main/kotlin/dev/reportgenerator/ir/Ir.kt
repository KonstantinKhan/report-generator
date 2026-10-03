package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Length

data class IrDocument(
    val pageSetup: PageSetup,
    val elements: List<IrElement>
)

sealed interface IrElement

data class IrText(
    val text: String,
    val style: TextStyle = Styles.mainText
) : IrElement

// STRETCH: the last filler row grows by the leftover (the page is covered down to the frame / margin). GAP: every
// filler row is exactly IrTable.rowHeight, the leftover stays empty below the last one.
enum class IrFillRemainder { STRETCH, GAP }

data class IrTable(
    val columns: List<IrColumn>,
    val header: IrTableHeader?,
    val content: List<IrTableElement>,
    val style: TableStyle = TableStyle(Styles.tableBorder),
    val rowHeight: Length? = null,
    // How a group's title row looks and what surrounds it; only used when rowHeight is fixed.
    val groupTitle: IrGroupTitle = IrGroupTitle(),
    // rowHeight != null only: cover each page down to the frame with blank bordered rows.
    val fillBlank: Boolean = true,
    // fillBlank only: what becomes of the page height left after the last whole filler row (see IrFillRemainder).
    val fillRemainder: IrFillRemainder = IrFillRemainder.STRETCH,
    // rowHeight != null only: total rows after the last group / row, before the blank fill (see IrTotalRow).
    val footer: List<IrTotalRow> = emptyList(),
    // rowHeight != null only: number the physical rows of the data records (see IrLineNumbers).
    val lineNumbers: IrLineNumbers? = null
) : IrElement

// Line numbering of a fixed-rowHeight table, done by the layout (the number depends on how the text wraps, so it
// cannot be known when the rows are built). Every PHYSICAL row of a data record (IrGroup.rows / IrRow, all the lines a
// wrapped record occupies) gets the next number in the cell of column `column`, in document order across pages; group
// title rows, spacers and total rows are not numbered and do not advance the counter. `start` = the first number (of
// the table, or of every page with PAGE). `fillBlank`: the blank filler rows (IrTable.fillBlank) are numbered too, in
// the same sequence; style / align are those of the numbered cell (the filler has no row cell of its own).
data class IrLineNumbers(
    val column: String,
    val start: Long = 1,
    val scope: IrLineScope = IrLineScope.TABLE,
    val fillBlank: Boolean = false,
    val style: TextStyle = Styles.tableText,
    val align: TextAlign = TextAlign.LEFT
)

enum class IrLineScope { TABLE, PAGE }

// The header text lives in IrTableHeader.cells (one per column, in column order), not in the column.
data class IrColumn(
    val id: String,
    val width: Length,
    val stickToFirstRow: Boolean = false,
    val stickToLastRow: Boolean = false
)

// A header cell on the header grid: origin `row` / `col` (0-based, columns of IrTable.columns), `span` columns and
// `rowSpan` rows; it is drawn as one rectangle over that area.
data class IrHeaderCell(val cell: IrCell, val row: Int, val col: Int, val span: Int = 1, val rowSpan: Int = 1)

// Multi-level header: row heights top to bottom + the cells with their grid areas (every column covered once per row).
data class IrHeaderGrid(val rowHeights: List<Length>, val cells: List<IrHeaderCell>)

// repeat: drawn on every page (false: first page only, later pages start at the top margin).
// Single-row header: `cells` (one per column, in column order) + `height`. Multi-level header: `grid`; `cells` is
// then empty and `height` is the total of the row heights.
data class IrTableHeader(
    val cells: List<IrCell>,
    val height: Length? = null,
    val repeat: Boolean = true,
    val grid: IrHeaderGrid? = null
) {
    // The grid of a fixed-height header; a single-row header is the one-row grid of its cells. Null: auto height.
    fun asGrid(): IrHeaderGrid? = grid ?: height?.let { h ->
        IrHeaderGrid(listOf(h), cells.mapIndexed { i, c -> IrHeaderCell(c, 0, i) })
    }
}

// Group title row of a fixed-rowHeight table. `column` (IrColumn.id) carries the title text, so the row has
// the same per-column cells as a data row; null = one full-width cell. spacerBefore / spacerAfter = blank
// bordered rows around it. keepWithRows: spacers, title and the group's first data line never split across
// pages. The defaults are the specification form's (ГОСТ 2.106).
data class IrGroupTitle(
    val column: String? = null,
    val style: TextStyle = Styles.groupHeader,
    val align: TextAlign = TextAlign.CENTER,
    val spacerBefore: Int = 2,
    val spacerAfter: Int = 1,
    val keepWithRows: Boolean = true
)

sealed interface IrTableElement

// footer: total rows right after the group's data rows (rowHeight != null only, see IrTotalRow).
data class IrGroup(
    val title: String,
    val rows: List<IrRow>,
    val constraints: LayoutConstraints = LayoutConstraints.Default,
    val footer: List<IrTotalRow> = emptyList()
) : IrTableElement

data class IrRow(
    val cells: List<IrCell>,
    val constraints: LayoutConstraints = LayoutConstraints.Default
) : IrTableElement

// Total row of a fixed-rowHeight table: one cell per column (the label and the value in their columns, the others
// empty), always drawn as a bordered row like any data row. In IrGroup.footer / IrTable.footer. The layout keeps
// the footer rows together with the line before them (the last data line of the group / table), so a total is
// never alone at the top of a page; a long label wraps into more physical rows like any cell.
data class IrTotalRow(val cells: List<IrCell>)

enum class TextOrientation { HORIZONTAL, VERTICAL_BOTTOM_TO_TOP }

enum class TextAlign { LEFT, CENTER }

data class IrCell(
    val text: String,
    val style: TextStyle = Styles.tableText,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    val align: TextAlign = TextAlign.LEFT,
    val manualLines: List<String>? = null
)
