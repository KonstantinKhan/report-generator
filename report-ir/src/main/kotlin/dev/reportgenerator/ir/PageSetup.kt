package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat

data class PageSetup(
    val format: PageFormat,
    val margins: Insets,
    val frameStyle: BorderStyle = Styles.tableBorder,
    val frame: FrameSpec? = null,
    val frameBindings: FrameBindings? = null,
    val continuationFrame: FrameSpec? = null,
    val leftMarginFrame: FrameSpec? = null,
    val belowFrame: FrameSpec? = null
)
