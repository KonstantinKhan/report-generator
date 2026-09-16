package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size

enum class FrameField { DESIGNATION, NAME, SHEET_NUMBER, SHEETS_TOTAL }

enum class BorderWeight { NONE, THIN, THICK }

data class CellBorders(
    val top: BorderWeight = BorderWeight.THICK,
    val right: BorderWeight = BorderWeight.THICK,
    val bottom: BorderWeight = BorderWeight.THICK,
    val left: BorderWeight = BorderWeight.THICK
)

sealed interface FrameCell {
    val rect: Rect
    val style: TextStyle
    val align: TextAlign
    val borders: CellBorders

    val orientation: TextOrientation

    data class Constant(
        override val rect: Rect,
        val text: String,
        override val style: TextStyle = Styles.frameText,
        override val align: TextAlign = TextAlign.LEFT,
        override val borders: CellBorders = CellBorders(),
        override val orientation: TextOrientation = TextOrientation.HORIZONTAL
    ) : FrameCell

    data class Dynamic(
        override val rect: Rect,
        val field: FrameField,
        override val style: TextStyle = Styles.frameText,
        override val align: TextAlign = TextAlign.LEFT,
        override val borders: CellBorders = CellBorders(),
        override val orientation: TextOrientation = TextOrientation.HORIZONTAL
    ) : FrameCell
}

data class FrameSpec(
    val size: Size,
    val cells: List<FrameCell>
)

data class FrameBindings(
    val designation: String? = null,
    val name: String? = null
)
