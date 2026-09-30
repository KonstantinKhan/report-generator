package dev.reportgenerator.ir.frames

import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.BorderWeight
import dev.reportgenerator.ir.CellBorders
import dev.reportgenerator.ir.FrameCell
import dev.reportgenerator.ir.FrameField
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.ir.TextAlign
import dev.reportgenerator.ir.TextOrientation

private fun rect(x: Int, y: Int, width: Int, height: Int): Rect = Rect(x.mm, y.mm, width.mm, height.mm)

private val ROW_ENDS = CellBorders(top = BorderWeight.THIN, bottom = BorderWeight.THIN)
private val NO_BORDERS = CellBorders(BorderWeight.NONE, BorderWeight.NONE, BorderWeight.NONE, BorderWeight.NONE)

// The 185x40mm ESKD title block ("основная надпись"), first sheet only, anchored at the
// bottom-right corner of the page frame. Two independent sub-grids stacked vertically: a 15mm
// header strip and a 25mm signature block below it whose columns don't line up with the header
// strip's, nor with each other row to row (real ESKD form 1 geometry).
//
// The header strip is 3 physical rows, not one merged row: Изм/Лист/№ докум./Подп./Дата live
// only on the 3rd (bottom) row, with two blank bordered rows above them. Обозначение (designation)
// is the exception — it stays merged across all 3 rows.
object FrameSpecs {
    val firstPageStamp: FrameSpec = FrameSpec(
        size = Size(185.mm, 40.mm),
        cells = listOf(
            // Header strip, y=0..15mm.
            FrameCell.Constant(rect(0, 0, 7, 5), ""),
            FrameCell.Constant(rect(7, 0, 10, 5), ""),
            FrameCell.Constant(rect(17, 0, 23, 5), ""),
            FrameCell.Constant(rect(40, 0, 15, 5), ""),
            FrameCell.Constant(rect(55, 0, 10, 5), ""),

            FrameCell.Constant(rect(0, 5, 7, 5), ""),
            FrameCell.Constant(rect(7, 5, 10, 5), ""),
            FrameCell.Constant(rect(17, 5, 23, 5), ""),
            FrameCell.Constant(rect(40, 5, 15, 5), ""),
            FrameCell.Constant(rect(55, 5, 10, 5), ""),

            FrameCell.Constant(rect(0, 10, 7, 5), "Изм", align = TextAlign.CENTER),
            FrameCell.Constant(rect(7, 10, 10, 5), "Лист", align = TextAlign.CENTER),
            FrameCell.Constant(rect(17, 10, 23, 5), "№ докум.", align = TextAlign.CENTER),
            FrameCell.Constant(rect(40, 10, 15, 5), "Подп.", align = TextAlign.CENTER),
            FrameCell.Constant(rect(55, 10, 10, 5), "Дата", align = TextAlign.CENTER),

            FrameCell.Dynamic(rect(65, 0, 120, 15), FrameField.DESIGNATION, style = Styles.frameTextLarge, align = TextAlign.CENTER),

            // Row 4 (y=15..20mm): signature label column + Наименование (spans rows 4-8) +
            // Лит./Лист/Листов.
            FrameCell.Constant(rect(0, 15, 17, 5), "Разраб.", align = TextAlign.LEFT, borders = CellBorders(bottom = BorderWeight.THIN)),
            FrameCell.Constant(rect(17, 15, 23, 5), "", borders = CellBorders(bottom = BorderWeight.THIN)),
            FrameCell.Constant(rect(40, 15, 15, 5), "", borders = CellBorders(bottom = BorderWeight.THIN)),
            FrameCell.Constant(rect(55, 15, 10, 5), "", borders = CellBorders(bottom = BorderWeight.THIN)),
            FrameCell.Dynamic(rect(65, 15, 70, 25), FrameField.NAME, style = Styles.frameTextLarge, align = TextAlign.CENTER),
            FrameCell.Constant(rect(135, 15, 15, 5), "Лит.", align = TextAlign.CENTER),
            FrameCell.Constant(rect(150, 15, 15, 5), "Лист", align = TextAlign.CENTER),
            FrameCell.Constant(rect(165, 15, 20, 5), "Листов", align = TextAlign.CENTER),

            // Row 5 (y=20..25mm): Пров. + three blank boxes + current sheet / sheets total.
            FrameCell.Constant(rect(0, 20, 17, 5), "Пров.", align = TextAlign.LEFT, borders = ROW_ENDS),
            FrameCell.Constant(rect(17, 20, 23, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(40, 20, 15, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(55, 20, 10, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(135, 20, 5, 5), ""),
            FrameCell.Constant(rect(140, 20, 5, 5), ""),
            FrameCell.Constant(rect(145, 20, 5, 5), ""),
            FrameCell.Dynamic(rect(150, 20, 15, 5), FrameField.SHEET_NUMBER, align = TextAlign.CENTER),
            FrameCell.Dynamic(rect(165, 20, 20, 5), FrameField.SHEETS_TOTAL, align = TextAlign.CENTER),

            // Row 6 (y=25..30mm): blank label column + blank box spanning rows 6-8.
            FrameCell.Constant(rect(0, 25, 17, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(17, 25, 23, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(40, 25, 15, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(55, 25, 10, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(135, 25, 50, 15), ""),

            // Row 7 (y=30..35mm): Н. контр.
            FrameCell.Constant(rect(0, 30, 17, 5), "Н. контр.", align = TextAlign.LEFT, borders = ROW_ENDS),
            FrameCell.Constant(rect(17, 30, 23, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(40, 30, 15, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(55, 30, 10, 5), "", borders = ROW_ENDS),

            // Row 8 (y=35..40mm): Утв.
            FrameCell.Constant(rect(0, 35, 17, 5), "Утв.", align = TextAlign.LEFT, borders = ROW_ENDS),
            FrameCell.Constant(rect(17, 35, 23, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(40, 35, 15, 5), "", borders = ROW_ENDS),
            FrameCell.Constant(rect(55, 35, 10, 5), "", borders = ROW_ENDS)
        )
    )

    // A 12x135mm strip meant for the main frame's bottom-left edge, rotated 90° (read bottom to
    // top, like the specification table's vertical header columns). Defined here in the block's
    // OWN top-left-origin local space, same convention as firstPageStamp — (0,0) is this strip's
    // own top, not a page coordinate. Anchoring it at the frame's bottom-left corner is handled
    // by resolveAnchor (leftMarginFrameOrigin, see LayoutEngine.kt). Content-area reservation is
    // union-based in PageLayoutMetrics.contentBottom(), which correctly excludes this block since
    // it sits outside the content column (x < margins.left).
    //
    // Along the strip (top to bottom in local space / bottom to top on the page): Подп. и дата /
    // Инв. № дубл. / Взам. инв. № / Подл. и дата / Инв. № подл. — the last one is nearest the
    // page corner (bottom of the strip once anchored). Across the strip: the 5mm labeled column
    // comes first (nearest the page edge), the 7mm blank margin sits behind it, against the frame.
    val leftMarginTable: FrameSpec = FrameSpec(
        size = Size(12.mm, 135.mm),
        cells = listOf(
            FrameCell.Constant(rect(0, 0, 5, 35), "Подп. и дата", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP),
            FrameCell.Constant(rect(5, 0, 7, 35), ""),

            FrameCell.Constant(rect(0, 35, 5, 25), "Инв. № дубл.", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP),
            FrameCell.Constant(rect(5, 35, 7, 25), ""),

            FrameCell.Constant(rect(0, 60, 5, 25), "Взам. инв. №", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP),
            FrameCell.Constant(rect(5, 60, 7, 25), ""),

            FrameCell.Constant(rect(0, 85, 5, 25), "Подл. и дата", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP),
            FrameCell.Constant(rect(5, 85, 7, 25), ""),

            FrameCell.Constant(rect(0, 110, 5, 25), "Инв. № подл.", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP),
            FrameCell.Constant(rect(5, 110, 7, 25), "")
        )
    )

    // "Копировал"/"Формат" notes below the main frame, in the sheet's own bottom-right margin
    // gutter (outside the frame, like leftMarginTable — no borders at all, plain captions). Block
    // is anchored with its right edge at the sheet's right edge (belowFrameOrigin in
    // LayoutEngine.kt), so local x here reads as "distance from the sheet's right edge, mirrored":
    // local x=30 is 90mm from the right edge, local x=90 is 30mm from it — matching the two
    // target centers given (~90mm and ~30mm from the sheet's right edge).
    val belowFrameNotes: FrameSpec = FrameSpec(
        size = Size(120.mm, 5.mm),
        cells = listOf(
            FrameCell.Constant(rect(10, 0, 40, 5), "Копировал", align = TextAlign.CENTER, borders = NO_BORDERS),
            FrameCell.Constant(rect(75, 0, 30, 5), "Формат", align = TextAlign.CENTER, borders = NO_BORDERS)
        )
    )

    // Specification left table: 12x120mm block rotated 90° (like leftMarginTable).
    // Two columns (5mm labeled, 7mm blank) spanning two rows vertically.
    // First row (y=0..60): "Справ. №" and blank; second row (y=60..120): "Перв. примен." and blank.
    // All borders thick.
    val specLeftTable: FrameSpec = FrameSpec(
        size = Size(12.mm, 120.mm),
        cells = listOf(
            FrameCell.Constant(rect(0, 0, 5, 60), "Справ. №", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP, borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),
            FrameCell.Constant(rect(5, 0, 7, 60), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),

            FrameCell.Constant(rect(0, 60, 5, 60), "Перв. примен.", align = TextAlign.CENTER, orientation = TextOrientation.VERTICAL_BOTTOM_TO_TOP, borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),
            FrameCell.Constant(rect(5, 60, 7, 60), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK))
        )
    )

    // Main title right table: 120x22mm block anchored to frame's top-right corner.
    // Three columns in first row (14+53+53=120mm, 14mm height), one column in second row (120mm, 8mm height).
    val mainTitleRightTable: FrameSpec = FrameSpec(
        size = Size(120.mm, 22.mm),
        cells = listOf(
            // First row: three cells 14mm, 53mm, 53mm wide, 14mm high
            FrameCell.Constant(rect(0, 0, 14, 14), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),
            FrameCell.Constant(rect(14, 0, 53, 14), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),
            FrameCell.Constant(rect(67, 0, 53, 14), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK)),

            // Second row: one cell 120mm wide, 8mm high
            FrameCell.Constant(rect(0, 14, 120, 8), "", borders = CellBorders(BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK, BorderWeight.THICK))
        )
    )

    // Continuation page stamp (§34.3 ЕСКД): 185x15mm stamp for pages 2+.
    // Only first 3 rows of header strip (two blank + row with Изм/Лист/№ докум./Подп./Дата).
    // No signature block rows below, unlike firstPageStamp.
    val continuationPageStamp: FrameSpec = FrameSpec(
        size = Size(185.mm, 15.mm),
        cells = listOf(
            // Header strip, y=0..15mm (same as firstPageStamp rows 0-3).
            FrameCell.Constant(rect(0, 0, 7, 5), ""),
            FrameCell.Constant(rect(7, 0, 10, 5), ""),
            FrameCell.Constant(rect(17, 0, 23, 5), ""),
            FrameCell.Constant(rect(40, 0, 15, 5), ""),
            FrameCell.Constant(rect(55, 0, 10, 5), ""),

            FrameCell.Constant(rect(0, 5, 7, 5), ""),
            FrameCell.Constant(rect(7, 5, 10, 5), ""),
            FrameCell.Constant(rect(17, 5, 23, 5), ""),
            FrameCell.Constant(rect(40, 5, 15, 5), ""),
            FrameCell.Constant(rect(55, 5, 10, 5), ""),

            FrameCell.Constant(rect(0, 10, 7, 5), "Изм", align = TextAlign.CENTER),
            FrameCell.Constant(rect(7, 10, 10, 5), "Лист", align = TextAlign.CENTER),
            FrameCell.Constant(rect(17, 10, 23, 5), "№ докум.", align = TextAlign.CENTER),
            FrameCell.Constant(rect(40, 10, 15, 5), "Подп.", align = TextAlign.CENTER),
            FrameCell.Constant(rect(55, 10, 10, 5), "Дата", align = TextAlign.CENTER),

            FrameCell.Dynamic(rect(65, 0, 120, 15), FrameField.DESIGNATION, style = Styles.frameTextLarge, align = TextAlign.CENTER),

            // Right column: sheet number
            FrameCell.Constant(rect(175, 0, 10, 5), "Лист", align = TextAlign.CENTER),
            FrameCell.Dynamic(rect(175, 5, 10, 10), FrameField.SHEET_NUMBER, align = TextAlign.CENTER)
        )
    )
}
