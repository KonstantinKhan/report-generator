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

data class IrTable(
    val columns: List<IrColumn>,
    val header: IrTableHeader?,
    val content: List<IrTableElement>,
    val style: TableStyle = TableStyle(Styles.tableBorder),
    val rowHeight: Length? = null,
    // How a group's title row looks and what surrounds it; only used when rowHeight is fixed.
    val groupTitle: IrGroupTitle = IrGroupTitle(),
    // rowHeight != null only: cover each page down to the frame with blank bordered rows.
    val fillBlank: Boolean = true
) : IrElement

// The header text lives in IrTableHeader.cells (one per column, in column order), not in the column.
data class IrColumn(
    val id: String,
    val width: Length,
    val stickToFirstRow: Boolean = false,
    val stickToLastRow: Boolean = false
)

// repeat: drawn on every page (false: first page only, later pages start at the top margin).
data class IrTableHeader(
    val cells: List<IrCell>,
    val height: Length? = null,
    val repeat: Boolean = true
)

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

data class IrGroup(
    val title: String,
    val rows: List<IrRow>,
    val constraints: LayoutConstraints = LayoutConstraints.Default
) : IrTableElement

data class IrRow(
    val cells: List<IrCell>,
    val constraints: LayoutConstraints = LayoutConstraints.Default
) : IrTableElement

enum class TextOrientation { HORIZONTAL, VERTICAL_BOTTOM_TO_TOP }

enum class TextAlign { LEFT, CENTER }

data class IrCell(
    val text: String,
    val style: TextStyle = Styles.tableText,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL,
    val align: TextAlign = TextAlign.LEFT,
    val manualLines: List<String>? = null
)
