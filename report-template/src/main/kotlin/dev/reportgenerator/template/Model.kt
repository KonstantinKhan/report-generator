package dev.reportgenerator.template

// Declarative template model, exactly as written in YAML (millimetres, unresolved params).
// Resolution to Length/Rect happens in TemplateResolver.

// Numeric field: literal or a `${param.x}` reference (only inside blockset definitions).
sealed interface Num {
    data class Lit(val value: Double) : Num
    data class Ref(val param: String) : Num
}

fun Double.lit(): Num = Num.Lit(this)

data class Vec(val x: Num, val y: Num) {
    companion object {
        val ZERO = Vec(Num.Lit(0.0), Num.Lit(0.0))
    }
}

data class SizeSpec(val width: Num, val height: Num)

enum class Orientation { PORTRAIT, LANDSCAPE }

enum class PageKind { FIRST, REST }

// `when:` in YAML.
enum class PageSelector {
    FIRST, REST, ALL;

    fun matches(kind: PageKind): Boolean = this == ALL || (this == FIRST && kind == PageKind.FIRST) ||
        (this == REST && kind == PageKind.REST)

    // Visibility of a block nested in an instance: both must hold. null = never visible.
    fun and(other: PageSelector): PageSelector? = when {
        this == ALL -> other
        other == ALL || other == this -> this
        else -> null
    }
}

enum class TextAlign { LEFT, CENTER, RIGHT }

data class Margins(val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0, val left: Double = 0.0)

data class SheetSpec(
    val format: String? = "A4",
    val width: Double? = null,
    val height: Double? = null,
    val orientation: Orientation = Orientation.PORTRAIT,
    val margins: Margins = Margins(),
    // Custom named points, mm from the sheet top-left.
    val anchors: Map<String, Vec> = emptyMap()
)

// One axis of an attach: `self` = this block's anchor, `to` = "blockId.anchorName", `offset` in mm along
// the sheet axis (x right / y down). Only that axis' coordinate of both anchors is used.
data class AxisAttach(val self: String = "topLeft", val to: String, val offset: Num = Num.Lit(0.0))

// Where a block goes. The x and y axes are placed independently (they may even target different blocks
// and anchors). The single form `attach: {self, to, offset: {x, y}}` is sugar for both axes with the
// same self/to; `perAxis` only records which YAML form was written (it selects the error paths).
data class AttachSpec(val x: AxisAttach, val y: AxisAttach, val perAxis: Boolean = false) {
    val axes: List<AxisAttach> get() = listOf(x, y)

    companion object {
        fun single(self: String = "topLeft", to: String, offset: Vec = Vec.ZERO) =
            AttachSpec(AxisAttach(self, to, offset.x), AxisAttach(self, to, offset.y))
    }
}

// Line weight keywords. Millimetres at Length resolution (1/100 mm) equal the layout engine's
// BorderWeight widths: THICK = Styles.tableBorder 2.0pt = 0.7056mm -> 0.71, THIN = Styles.tableBorderThin
// 0.7pt = 0.2469mm -> 0.25 (see report-ir Styles / LayoutEngine.ptToLength).
enum class LineWeight(val key: String, val mm: Double) {
    NONE("none", 0.0), THIN("thin", 0.25), THICK("thick", 0.71);

    companion object {
        fun byKey(key: String): LineWeight? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

// Cell borders; a null side inherits (cell -> table default -> THICK).
data class BorderSpec(
    val top: LineWeight? = null,
    val right: LineWeight? = null,
    val bottom: LineWeight? = null,
    val left: LineWeight? = null
) {
    companion object {
        fun all(weight: LineWeight) = BorderSpec(weight, weight, weight, weight)
    }
}

sealed interface BlockSpec {
    val id: String
    val anchors: Map<String, Vec>
    val attach: AttachSpec?
    val visibleOn: PageSelector
    val reserves: Boolean
}

data class FrameBlock(
    override val id: String,
    val size: SizeSpec,
    val thickness: Num = Num.Lit(LineWeight.THICK.mm),
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

data class RectBlock(
    override val id: String,
    val size: SizeSpec,
    val thickness: Num = Num.Lit(LineWeight.THIN.mm),
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

data class TextBlock(
    override val id: String,
    val size: SizeSpec,
    val text: String? = null,
    val bind: String? = null,
    val format: FormatSpec? = null,
    val optional: Boolean = false,
    val align: TextAlign = TextAlign.LEFT,
    val rotate: Int = 0,
    val fontSize: Num? = null,
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

// Data-driven table region. Without `size` it fills the flow region (sheet content area minus reserves).
data class FlowBlock(
    override val id: String,
    val size: SizeSpec? = null,
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

// `format` / `optional` only go with `bind` (see FormatSpec, Binding).
// `span` = columns, `rowSpan` = rows (the cell covers the rows below it in its columns; later rows leave those
// columns out of their cell list). `style` is an opaque style key the consumer maps to its own text style.
data class CellSpec(
    val text: String? = null,
    val bind: String? = null,
    val format: FormatSpec? = null,
    val optional: Boolean = false,
    val span: Int = 1,
    val rowSpan: Int = 1,
    val rotate: Int = 0,
    val align: TextAlign = TextAlign.LEFT,
    val fontSize: Num? = null,
    val style: String? = null,
    val borders: BorderSpec? = null
)

sealed interface RowSpec

data class FixedRow(val height: Num, val cells: List<CellSpec>) : RowSpec

// Exactly one of `count` (static, expanded now) / `from` (data path, resolved by the engine later).
data class RepeatRows(val count: Int?, val from: String?, val row: FixedRow) : RowSpec

// `rotate` (0|90|270, counterclockwise, 90 = reads bottom to top): the table is defined unrotated (its own
// w x h grid); the block occupies the rotated bounding box (h x w) with its top-left at the placement point.
data class TableBlock(
    override val id: String,
    val columns: List<Num>,
    val rows: List<RowSpec>,
    val rotate: Int = 0,
    val borders: BorderSpec? = null,
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

// `args` values are raw strings (may contain `${param.x}` of the enclosing definition).
data class BlockSetInstance(
    override val id: String,
    val use: String,
    val args: Map<String, String> = emptyMap(),
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

// `params`: name -> default (null = required). `ports`: exposed name -> "childId.anchor".
data class BlockSetDef(
    val name: String,
    val params: Map<String, String?>,
    val ports: Map<String, String>,
    val blocks: List<BlockSpec>
)

data class Template(
    val name: String,
    val sheet: SheetSpec,
    // Where blocks without `attach` go.
    val root: AttachSpec = AttachSpec.single(self = "topLeft", to = "sheet.topLeft"),
    val blocksets: Map<String, BlockSetDef> = emptyMap(),
    val blocks: List<BlockSpec>
)
