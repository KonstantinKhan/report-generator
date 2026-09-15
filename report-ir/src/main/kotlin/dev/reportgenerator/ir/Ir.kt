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
    val style: TableStyle = TableStyle(Styles.tableBorder)
) : IrElement

data class IrColumn(
    val id: String,
    val width: Length,
    val header: String? = null
)

data class IrTableHeader(val cells: List<IrCell>)

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

data class IrCell(
    val text: String,
    val style: TextStyle = Styles.tableText
)
