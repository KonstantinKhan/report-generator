package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.mm

fun document(block: DocumentBuilder.() -> Unit): IrDocument {
    val builder = DocumentBuilder()
    builder.block()
    return builder.build()
}

class DocumentBuilder {
    private var currentPageSetup: PageSetup = PageSetup(
        format = PageFormat.A4,
        margins = Insets(top = 5.mm, right = 5.mm, bottom = 5.mm, left = 20.mm)
    )
    private val elements = mutableListOf<IrElement>()

    fun pageSetup(
        format: PageFormat = currentPageSetup.format,
        margins: Insets = currentPageSetup.margins,
        frame: FrameSpec? = currentPageSetup.frame,
        frameBindings: FrameBindings? = currentPageSetup.frameBindings,
        continuationFrame: FrameSpec? = currentPageSetup.continuationFrame,
        leftMarginFrame: FrameSpec? = currentPageSetup.leftMarginFrame,
        belowFrame: FrameSpec? = currentPageSetup.belowFrame,
        specLeftTable: FrameSpec? = currentPageSetup.specLeftTable
    ) {
        currentPageSetup = PageSetup(
            format, margins,
            frame = frame, frameBindings = frameBindings,
            continuationFrame = continuationFrame,
            leftMarginFrame = leftMarginFrame, belowFrame = belowFrame,
            specLeftTable = specLeftTable
        )
    }

    fun title(text: String, style: TextStyle = Styles.heading) {
        elements += IrText(text, style)
    }

    fun table(rowHeight: Length? = null, groupTitleColumn: String? = null, block: TableBuilder.() -> Unit) {
        val builder = TableBuilder(rowHeight, groupTitleColumn)
        builder.block()
        elements += builder.build()
    }

    fun build(): IrDocument = IrDocument(currentPageSetup, elements)
}

class TableBuilder(private val rowHeight: Length? = null, private val groupTitleColumn: String? = null) {
    private val columns = mutableListOf<IrColumn>()
    private val content = mutableListOf<IrTableElement>()
    private var header: IrTableHeader? = null
    private var style: TableStyle = TableStyle(Styles.tableBorder)

    fun columns(block: ColumnsBuilder.() -> Unit) {
        columns += ColumnsBuilder().apply(block).build()
    }

    fun header(vararg cells: String) {
        header = IrTableHeader(cells.map { IrCell(it) })
    }

    fun header(height: Length, block: HeaderBuilder.() -> Unit) {
        header = IrTableHeader(HeaderBuilder().apply(block).build(), height = height)
    }

    fun group(
        title: String,
        constraints: LayoutConstraints = LayoutConstraints.Default,
        block: GroupBuilder.() -> Unit
    ) {
        content += GroupBuilder(title, constraints).apply(block).build()
    }

    fun build(): IrTable = IrTable(columns, header, content, style, rowHeight, groupTitleColumn)
}

class HeaderBuilder {
    private val cells = mutableListOf<IrCell>()

    fun cell(
        text: String,
        orientation: TextOrientation = TextOrientation.HORIZONTAL,
        align: TextAlign = TextAlign.CENTER,
        style: TextStyle = Styles.tableHeader,
        manualLines: List<String>? = null
    ) {
        cells += IrCell(text = text, style = style, orientation = orientation, align = align, manualLines = manualLines)
    }

    fun build(): List<IrCell> = cells
}

class ColumnsBuilder {
    private val columns = mutableListOf<IrColumn>()

    fun column(id: String, width: Length, header: String? = null, stickToFirstRow: Boolean = false, stickToLastRow: Boolean = false) {
        columns += IrColumn(id, width, header, stickToFirstRow, stickToLastRow)
    }

    fun build(): List<IrColumn> = columns
}

class GroupBuilder(
    private val title: String,
    private val constraints: LayoutConstraints
) {
    private val rows = mutableListOf<IrRow>()

    fun row(cells: List<IrCell>, constraints: LayoutConstraints = LayoutConstraints.Default) {
        rows += IrRow(cells, constraints)
    }

    fun row(vararg texts: String) {
        rows += IrRow(texts.map { IrCell(it) })
    }

    fun build(): IrGroup = IrGroup(title, rows, constraints)
}
