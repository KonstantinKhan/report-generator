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
    // Column id (IrColumn.id) that carries a group's title text when rowHeight is fixed — the
    // title row then has the same per-column cells as a data row, instead of one full-width cell.
    // null keeps the old full-width single-cell title row.
    val groupTitleColumn: String? = null
) : IrElement

data class IrColumn(
    val id: String,
    val width: Length,
    val header: String? = null,
    val stickToLastRow: Boolean = false
)

data class IrTableHeader(
    val cells: List<IrCell>,
    val height: Length? = null
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
