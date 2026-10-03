package dev.reportgenerator.template

import java.math.RoundingMode

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
// `table` describes the main (flow) table: structure and style only, the layout algorithm (measure, wrap,
// pagination) stays in the engine. A flow with a table fills the flow region, so it has no `size`.
data class FlowBlock(
    override val id: String,
    val size: SizeSpec? = null,
    val table: FlowTableSpec? = null,
    override val anchors: Map<String, Vec> = emptyMap(),
    override val attach: AttachSpec? = null,
    override val visibleOn: PageSelector = PageSelector.ALL,
    override val reserves: Boolean = false
) : BlockSpec

// `stickToFirstRow` / `stickToLastRow` of the engine: a single-line value of a row wrapped by another column
// sits on the first / last physical line of that row.
enum class FlowStick(val key: String) {
    NONE("none"), FIRST("first"), LAST("last");

    companion object {
        fun byKey(key: String): FlowStick? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

// `align` is the default for the column's row cell (a row cell's own `align` wins). Millimetres, no params.
data class FlowColumn(val id: String, val width: Double, val stick: FlowStick = FlowStick.NONE, val align: TextAlign = TextAlign.LEFT)

// `text` is the logical text, `lines` an optional manual line break of it (no auto-wrap then), `rotate` 0|90.
// `style`: a key of `FlowTableSpec.styles` or a style name the consumer knows (null = consumer default).
// `span` (columns) / `rowSpan` (rows) only in the `rows` form of the header (1 in the single-row form).
data class FlowHeaderCell(
    val text: String,
    val lines: List<String>? = null,
    val rotate: Int = 0,
    val align: TextAlign = TextAlign.CENTER,
    val style: String? = null,
    val span: Int = 1,
    val rowSpan: Int = 1
)

// One row of the multi-level header: `cells` in grid column order (placed over the table columns, not by id),
// only the columns not taken by a rowSpan from above.
data class FlowHeaderRow(val height: Double, val cells: List<FlowHeaderCell>)

// Single-row form: `cells` keyed by column id, must cover every column, `height` is the row height.
// Multi-level form: `rows` (not empty, `cells` empty), `height` = the sum of the row heights.
// `repeat`: header on every page, not only the first.
data class FlowHeader(
    val height: Double,
    val repeat: Boolean = true,
    val cells: Map<String, FlowHeaderCell>,
    val rows: List<FlowHeaderRow> = emptyList()
)

// The group title row has the same per-column cells as a row; the title text goes into `column`.
// `spacerBefore` / `spacerAfter` = blank bordered rows around the title.
data class FlowGroupTitle(
    val column: String,
    val style: String? = null,
    val align: TextAlign = TextAlign.CENTER,
    val spacerBefore: Int = 0,
    val spacerAfter: Int = 0
)

// Structured, closed row predicate (flow table `where`, `groupBy`-less filtering, `cases`). `field` is a field of
// the row record `item` (no root prefix). Literals are kept as written and typed against the field's type by the
// contract (Enum: a member of the schema's values, Integer, Decimal, Boolean, ISO Date, String). No expressions.
sealed interface Predicate {
    data class Eq(val field: String, val value: String) : Predicate
    data class Ne(val field: String, val value: String) : Predicate
    data class In(val field: String, val values: List<String>) : Predicate
    data class IsNull(val field: String) : Predicate
    data class NotNull(val field: String) : Predicate
    data class And(val items: List<Predicate>) : Predicate
    data class Or(val items: List<Predicate>) : Predicate
    data class Not(val item: Predicate) : Predicate
}

enum class SortOrder { ASC, DESC }

enum class NullsOrder { FIRST, LAST }

// One `sortBy` key. `nulls` places rows without a value for the field first / last regardless of `order`.
data class FlowSort(val field: String, val order: SortOrder = SortOrder.ASC, val nulls: NullsOrder = NullsOrder.LAST)

// `scope`: one running number over the whole table (group order) or restarted in every group.
enum class SequenceScope { TABLE, GROUP }

// Operand of an arithmetic computed field: a field of the row record (raw or another arithmetic computed field) or
// a numeric literal as written in YAML (no '.' = Integer, else Decimal). The loader tells them apart by shape.
sealed interface Operand {
    data class Field(val name: String) : Operand
    data class Literal(val text: String) : Operand
}

enum class ArithOp(val key: String) {
    MULTIPLY("multiply"), ADD("add"), SUBTRACT("subtract"), DIVIDE("divide");

    companion object {
        fun byKey(key: String): ArithOp? = entries.firstOrNull { it.key == key }
    }
}

// `computed:` entries, a closed set. `sequence` gives an Integer: start, start + step, ...
// Arithmetic: one operation over numeric operands, BigDecimal / Long exact. `multiply` / `add` take 2+ operands,
// `subtract` / `divide` exactly 2. Integer op Integer = Integer (not `divide`), anything with a Decimal = Decimal,
// `divide` = Decimal. `scale` + `rounding` round a Decimal result (`divide` needs both, the others may skip them);
// an Integer result takes neither. A row with no value for an operand has no value for the result.
sealed interface FlowComputed {
    data class Sequence(val scope: SequenceScope = SequenceScope.TABLE, val start: Long = 1, val step: Long = 1) : FlowComputed
    data class Arithmetic(
        val op: ArithOp,
        val operands: List<Operand>,
        val scale: Int? = null,
        val rounding: RoundingMode? = null
    ) : FlowComputed
}

// Rows are split by an Enum field of the row record into groups, in `order`; `titles` = the group title text of
// every value in `order`. `skipEmpty` (default): a value without rows makes no group (no title, no spacers),
// false: an empty group is kept. `omit`: values that are dropped on purpose; every value the field can have must
// be in `order` or `omit` (contract), so no row is lost silently.
data class FlowGroupBy(
    val field: String,
    val order: List<String>,
    val titles: Map<String, String>,
    val skipEmpty: Boolean = true,
    val omit: List<String> = emptyList()
)

// One variant of a cell: `where` true -> this text / bind (with `format`, `optional`) is the cell's content.
data class FlowCase(
    val where: Predicate,
    val text: String? = null,
    val bind: String? = null,
    val format: FormatSpec? = null,
    val optional: Boolean = false
)

// Row cell: literal `text` or `bind` (root `item`, one record of the data the code supplies), `format` /
// `optional` as for any bind. `align` null = the column's. No cell content = empty cell.
// `cases`: the first case whose `where` holds wins; otherwise the cell's own text / bind / format / optional
// (the default); no case matching and no default content = empty.
data class FlowRowCell(
    val text: String? = null,
    val bind: String? = null,
    val format: FormatSpec? = null,
    val optional: Boolean = false,
    val align: TextAlign? = null,
    val style: String? = null,
    val cases: List<FlowCase> = emptyList()
)

enum class FlowFill { NONE, BLANK }

// Bind of the row cell that shows the number of the physical line (layout-derived, see FlowLines).
const val LINE_NUMBER_BIND = "\${line.number}"

// `scope`: one running line number over the whole table (all pages) or restarted on every page.
enum class LinesScope { TABLE, PAGE }

// `lines:` of a flow table: numbering of the PHYSICAL rows (every line a record occupies once its text is wrapped), shown
// by the row cell bound to `${line.number}`. `start` = the number of the first line (of the table / of every page).
// `fill`: the blank filler rows (`fill: blank`) take numbers too (default: they stay empty, the owner decides per form).
// Group titles, spacers and total rows are never numbered.
data class FlowLines(val start: Long = 1, val scope: LinesScope = LinesScope.TABLE, val fill: Boolean = false)

// `titleChain`: spacers + group title + the first data line stay together (a title is never left alone
// at the bottom of a page).
data class FlowKeep(val titleChain: Boolean = true)

// `totals:` entry: one aggregate over the rows of a group (needs `groupBy`) or of the whole table, drawn as a
// bordered row after the group's data rows / after the last group: `label` in `labelColumn`, the result in
// `valueColumn`, the other cells empty. `field` is an item field or a computed field (Integer / Decimal; not for
// `count`). `where` keeps only matching rows in the aggregate. `skipEmpty` (default): no row when the scope has no
// rows at all. `scale` + `rounding` only for `avg` (both required).
enum class TotalScope { GROUP, TABLE }

enum class TotalAgg(val key: String) {
    SUM("sum"), COUNT("count"), MIN("min"), MAX("max"), AVG("avg");

    companion object {
        fun byKey(key: String): TotalAgg? = entries.firstOrNull { it.key == key }
    }
}

data class FlowTotal(
    val id: String,
    val scope: TotalScope,
    val agg: TotalAgg,
    val field: String? = null,
    val label: String,
    val labelColumn: String,
    val valueColumn: String,
    val format: FormatSpec? = null,
    val style: String? = null,
    val where: Predicate? = null,
    val skipEmpty: Boolean = true,
    val scale: Int? = null,
    val rounding: RoundingMode? = null
)

// `styles:` entry of a flow table: `base` = a style name the consumer knows (required), the other fields override it
// (null = inherit from `base`). `size` = font size in mm. YAML: `alias: baseName` (= FlowStyle(baseName)) or
// `alias: {base: baseName, size: 4.5, bold: true, italic: false, underline: false}`.
data class FlowStyle(
    val base: String,
    val size: Double? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    // only records which YAML form was written (it selects the error path of `base`)
    val asObject: Boolean = false
)

// `styles`: alias -> style (a built-in name or an overridden copy of one, see FlowStyle); cells may use either an alias
// or a built-in name.
// Data shaping, in this order: `where` (filter rows) -> `sortBy` (stable; none = source order) -> `groupBy`
// (none = a flat table; groups follow `order`, rows keep their order inside) -> `computed` (numbers over the
// final order, so `scope: table` runs through the groups in table order). `where`, `groupBy` read the record's own
// fields; `sortBy` also reads arithmetic `computed` fields (they are evaluated right after `where`, a `sequence` is not);
// `computed` names are visible to row cells, `cases`, `sortBy` (arithmetic) and `totals`. `totals`: see FlowTotal.
data class FlowTableSpec(
    val rowHeight: Double,
    val columns: List<FlowColumn>,
    val header: FlowHeader? = null,
    val groupTitle: FlowGroupTitle? = null,
    val rowCells: Map<String, FlowRowCell>,
    val fill: FlowFill = FlowFill.NONE,
    val keep: FlowKeep = FlowKeep(),
    val styles: Map<String, FlowStyle> = emptyMap(),
    val where: Predicate? = null,
    val sortBy: List<FlowSort> = emptyList(),
    val groupBy: FlowGroupBy? = null,
    val computed: Map<String, FlowComputed> = emptyMap(),
    val totals: List<FlowTotal> = emptyList(),
    // null = no `lines:` section (the defaults of FlowLines apply to a table that numbers its lines)
    val lines: FlowLines? = null
)

// Id of the column whose row cell is bound to `${line.number}` (null: the table does not number its lines).
fun FlowTableSpec.lineNumberColumn(): String? = rowCells.entries.firstOrNull { it.value.bind == LINE_NUMBER_BIND }?.key

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
