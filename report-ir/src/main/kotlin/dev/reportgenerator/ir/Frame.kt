package dev.reportgenerator.ir

import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataSchema
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.FormatSpec
import dev.reportgenerator.template.dataSchema

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

    // Text comes from the page's DataContext at layout time: `path` is a bind path without `${}`
    // ("doc.designation", "page.number"), rendered with `format`; a missing value is an error unless `optional`.
    data class Dynamic(
        override val rect: Rect,
        val path: String,
        override val style: TextStyle = Styles.frameText,
        override val align: TextAlign = TextAlign.LEFT,
        override val borders: CellBorders = CellBorders(),
        override val orientation: TextOrientation = TextOrientation.HORIZONTAL,
        val format: FormatSpec? = null,
        val optional: Boolean = false
    ) : FrameCell
}

data class FrameSpec(
    val size: Size,
    val cells: List<FrameCell>
)

// Convenience DataContext for the two classic stamp fields (doc.designation, doc.name).
data class FrameBindings(
    val designation: String? = null,
    val name: String? = null
) : DataContext {
    override val schema: DataSchema get() = SCHEMA

    override fun get(path: String): DataValue? = when (path) {
        "doc.designation" -> designation?.let(DataValue::of)
        "doc.name" -> name?.let(DataValue::of)
        else -> null
    }

    private companion object {
        val SCHEMA = dataSchema { doc { string("designation"); string("name") } }
    }
}
