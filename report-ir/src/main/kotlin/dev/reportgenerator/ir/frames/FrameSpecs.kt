package dev.reportgenerator.ir.frames

import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.StaticSlot

// The static blocks of the ESKD specification sheet as FrameSpecs (cells in each block's OWN
// top-left-origin local space). Geometry, texts, borders and bindings live in
// resources/templates/gost-spec.yaml (see GostSpecTemplate); this object only exposes them in the form
// PageSetup takes. Where a block goes on the page (anchor, offset, first/rest pages, content
// reservation) is also declared there and applied by the layout engine.
//
// Block summary (mm):
//  - firstPageStamp (slot FRAME)            185x40 title block, page 1, nested in the frame's bottom-right
//    corner. Header strip (3 physical rows; Изм/Лист/№ докум./Подп./Дата on the 3rd; designation merged over
//    all 3) over a signature block with its own column grid (real ESKD form 1 geometry).
//  - leftMarginTable (LEFT_MARGIN)          12x135 strip outside the frame's left edge, labels bottom to top.
//  - belowFrameNotes (BELOW_FRAME)          120x5 "Копировал"/"Формат", borderless, in the sheet's bottom-right
//    gutter below the frame.
//  - specLeftTable (SPEC_LEFT)              12x120 strip off the frame's top-left corner, thick borders.
//  - mainTitleRightTable (MAIN_TITLE_RIGHT) 120x22 table in the frame's bottom-right corner, 40mm up.
//  - continuationPageStamp (CONTINUATION_FRAME) 185x15 stamp of pages 2+ (ESKD 2.104 §34.3).
object FrameSpecs {
    val firstPageStamp: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.FRAME)

    val leftMarginTable: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.LEFT_MARGIN)

    val belowFrameNotes: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.BELOW_FRAME)

    val specLeftTable: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.SPEC_LEFT)

    val mainTitleRightTable: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.MAIN_TITLE_RIGHT)

    val continuationPageStamp: FrameSpec = GostSpecTemplate.frameSpec(StaticSlot.CONTINUATION_FRAME)
}
