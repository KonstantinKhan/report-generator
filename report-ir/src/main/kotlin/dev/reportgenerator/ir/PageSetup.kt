package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.PageFormat

data class PageSetup(
    val format: PageFormat,
    val margins: Insets,
    val frameStyle: BorderStyle = Styles.tableBorder,
    val titleBlock: TitleBlockSpec? = null
)

data class TitleBlockSpec(
    val designation: String,
    val name: String,
    val sheetsTotal: Int = 1
)
