package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.ir.frames.GostSpecTemplate
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.Template

data class PageSetup(
    val format: PageFormat,
    val margins: Insets,
    val frameStyle: BorderStyle = Styles.tableBorder,
    val frame: FrameSpec? = null,
    // Values for dynamic cells of the static blocks (doc.*, item.*); page.number / page.total are added per page.
    // FrameBindings(designation, name) is a ready-made one for the classic stamp.
    val dataContext: DataContext? = null,
    val continuationFrame: FrameSpec? = null,
    val leftMarginFrame: FrameSpec? = null,
    val belowFrame: FrameSpec? = null,
    val specLeftTable: FrameSpec? = null,
    val mainTitleRightTable: FrameSpec? = null,
    // Declares where each static block (slot above) is anchored, on which pages, and what it reserves.
    val staticTemplate: Template = GostSpecTemplate.template
) {
    fun frameSpecOf(slot: StaticSlot): FrameSpec? = when (slot) {
        StaticSlot.FRAME -> frame
        StaticSlot.CONTINUATION_FRAME -> continuationFrame
        StaticSlot.LEFT_MARGIN -> leftMarginFrame
        StaticSlot.SPEC_LEFT -> specLeftTable
        StaticSlot.MAIN_TITLE_RIGHT -> mainTitleRightTable
        StaticSlot.BELOW_FRAME -> belowFrame
    }
}
