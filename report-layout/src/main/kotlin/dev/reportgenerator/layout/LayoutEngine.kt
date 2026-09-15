package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.IrRow
import dev.reportgenerator.ir.IrTable
import dev.reportgenerator.ir.LayoutConstraints
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.ir.TitleBlockSpec
import dev.reportgenerator.ir.BorderStyle as IrBorderStyle
import dev.reportgenerator.layoutir.BorderStyle as LayoutBorderStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.layoutir.Page
import dev.reportgenerator.layoutir.PageElement
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.layoutir.Rectangle
import dev.reportgenerator.layoutir.ResolvedTextStyle

private val TITLE_BLOCK_HEIGHT: Length = 20.mm
private val TITLE_LINE_HEIGHT: Length = 5.mm
private const val PT_TO_MM = 25.4 / 72.0

class LayoutOverflowException(
    val elementPath: String,
    val constraint: String,
    val pageNumber: Int,
    message: String
) : RuntimeException(message)

data class PageLayoutMetrics(
    val format: PageFormat,
    val margins: Insets,
    val headerHeight: Length,
    val titleBlockHeight: Length
) {
    val contentTop: Length get() = margins.top + headerHeight

    fun contentBottom(isFirstPage: Boolean): Length {
        val reserved = if (isFirstPage) titleBlockHeight else Length.ZERO
        return format.height - margins.bottom - reserved
    }
}

fun layOut(
    document: IrDocument,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef
): LaidOutDocument {
    val setup = document.pageSetup
    val table = document.elements.filterIsInstance<IrTable>().firstOrNull()
    val titleBlockHeight = if (setup.titleBlock != null) TITLE_BLOCK_HEIGHT else Length.ZERO

    if (table == null) {
        val metrics = PageLayoutMetrics(setup.format, setup.margins, Length.ZERO, titleBlockHeight)
        val elements = mutableListOf<PageElement>()
        elements += frameRectangle(metrics, setup.frameStyle)
        setup.titleBlock?.let { elements += drawTitleBlock(it, metrics, fontResolver) }
        return LaidOutDocument(listOf(Page(1, setup.format, elements)))
    }

    val contentLeft = setup.margins.left
    val contentWidth = setup.format.width - setup.margins.left - setup.margins.right
    val offsets = columnOffsets(table.columns, contentLeft)

    val headerRow = table.header?.let { header ->
        measureRow(IrRow(header.cells), table.columns, offsets, textMeasurer)
    }

    val blocks = buildBlocks(table, offsets, contentWidth, contentLeft, textMeasurer, "Document/Table")
    val units = groupIntoUnits(blocks)

    val metrics = PageLayoutMetrics(
        format = setup.format,
        margins = setup.margins,
        headerHeight = headerRow?.height ?: Length.ZERO,
        titleBlockHeight = titleBlockHeight
    )

    return renderPages(units, metrics, headerRow, setup, fontResolver)
}

private fun renderPages(
    units: List<List<LayoutBlock>>,
    metrics: PageLayoutMetrics,
    headerRow: MeasuredRow?,
    setup: PageSetup,
    fontResolver: (TextStyle) -> FontRef
): LaidOutDocument {
    val pages = mutableListOf<Page>()
    var elements = mutableListOf<PageElement>()
    var y = metrics.contentTop
    var pageNumber = 1

    fun isFirstPage() = pageNumber == 1

    fun drawChrome() {
        elements += frameRectangle(metrics, setup.frameStyle)
        headerRow?.let { elements += drawRow(it, metrics.margins.top, fontResolver) }
        if (isFirstPage()) {
            setup.titleBlock?.let { elements += drawTitleBlock(it, metrics, fontResolver) }
        }
    }

    fun finishPage() {
        drawChrome()
        pages += Page(pageNumber, metrics.format, elements)
    }

    fun startNewPage() {
        finishPage()
        pageNumber += 1
        elements = mutableListOf()
        y = metrics.contentTop
    }

    for (unit in units) {
        val unitHeight = unit.fold(Length.ZERO) { acc, block -> acc + block.height }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            if (elements.isNotEmpty() || isFirstPage()) {
                startNewPage()
            }
        }

        if (y + unitHeight > metrics.contentBottom(isFirstPage())) {
            val first = unit.first()
            throw LayoutOverflowException(
                elementPath = first.path,
                constraint = describeConstraint(first.constraints),
                pageNumber = pageNumber,
                message = "Block '${first.path}' height ${unitHeight.toMillimeters()}mm exceeds available content height on page $pageNumber"
            )
        }

        for (block in unit) {
            elements += drawRow(block.row, y, fontResolver)
            y += block.height
        }
    }

    finishPage()
    return LaidOutDocument(pages)
}

private fun describeConstraint(constraints: LayoutConstraints): String = when {
    constraints.keepTogether -> "keepTogether"
    constraints.keepWithNext -> "keepWithNext"
    else -> "content overflow"
}

private fun drawRow(row: MeasuredRow, top: Length, fontResolver: (TextStyle) -> FontRef): List<PageElement> =
    row.lines.map { line ->
        PositionedText(
            text = line.text,
            rect = Rect(
                x = line.x,
                y = top + row.lineHeight * line.lineIndex,
                width = line.width,
                height = row.lineHeight
            ),
            style = ResolvedTextStyle(fontResolver(line.style), line.style.fontSizePt)
        )
    }

private fun ptToLength(pt: Double): Length = Length.ofMillimeters(pt * PT_TO_MM)

private fun frameRectangle(metrics: PageLayoutMetrics, style: IrBorderStyle): Rectangle =
    Rectangle(
        rect = Rect(
            x = metrics.margins.left,
            y = metrics.margins.top,
            width = metrics.format.width - metrics.margins.left - metrics.margins.right,
            height = metrics.format.height - metrics.margins.top - metrics.margins.bottom
        ),
        style = LayoutBorderStyle(width = ptToLength(style.widthPt))
    )

private fun drawTitleBlock(
    spec: TitleBlockSpec,
    metrics: PageLayoutMetrics,
    fontResolver: (TextStyle) -> FontRef
): List<PageElement> {
    val top = metrics.format.height - metrics.margins.bottom - TITLE_BLOCK_HEIGHT
    val left = metrics.margins.left
    val width = metrics.format.width - metrics.margins.left - metrics.margins.right
    val style = Styles.designation

    val box = Rectangle(
        rect = Rect(left, top, width, TITLE_BLOCK_HEIGHT),
        style = LayoutBorderStyle(width = ptToLength(Styles.tableBorder.widthPt))
    )

    val lines = listOf(
        "Обозначение: ${spec.designation}",
        "Наименование: ${spec.name}",
        "Лист 1 из ${spec.sheetsTotal}"
    )

    val texts = lines.mapIndexed { index, text ->
        PositionedText(
            text = text,
            rect = Rect(left + 2.mm, top + 2.mm + TITLE_LINE_HEIGHT * index, width - 4.mm, TITLE_LINE_HEIGHT),
            style = ResolvedTextStyle(fontResolver(style), style.fontSizePt)
        )
    }

    return listOf(box) + texts
}
