package dev.reportgenerator.template

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path

// YAML -> Template. Reads the kaml node tree by hand (no reflection-based deserialization) to get
// strict unknown-field checks and precise `blocks[2].attach.to`-style paths. Structural problems
// (shape, types) fail fast here; semantic ones (references, cycles) are TemplateValidator's job.
object TemplateLoader {
    fun parse(yaml: String): Template {
        val root = try {
            Yaml.default.parseToYamlNode(yaml)
        } catch (e: YamlException) {
            throw TemplateException("", "invalid YAML: ${e.message} (line ${e.line}, column ${e.column})")
        }
        return Reader().template(root)
    }

    // parse + validate. `styleNames` = the text style names the consumer knows (checked in flow tables).
    fun load(yaml: String, styleNames: Set<String>? = null): Template =
        parse(yaml).also { TemplateValidator.require(it, styleNames) }

    fun load(path: Path, styleNames: Set<String>? = null): Template = load(Files.readString(path), styleNames)
}

internal val PARAM_REF = Regex("""\$\{param\.([A-Za-z_][A-Za-z0-9_]*)}""")
internal val BIND_EXPR = Regex("""\$\{(doc|page|item|line)\.[A-Za-z_][A-Za-z0-9_.]*}""")
internal val ID_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")
// Numeric operand of an arithmetic computed field; a field name never starts with a digit or a sign.
internal val NUMBER_LITERAL = Regex("-?\\d+(\\.\\d+)?")

private val COMMON_KEYS = setOf("id", "type", "anchors", "attach", "when", "reserves")

private class Reader {
    // Params visible while reading a blockset definition; empty at top level.
    private var params: Set<String> = emptySet()

    fun template(node: YamlNode): Template {
        val m = node.asMap("")
        m.allow("", "name", "sheet", "root", "blocksets", "blocks")
        val blocksets = m.optional("blocksets")?.let { n ->
            n.asMap("blocksets").entries.entries.associate { (k, v) ->
                k.content to blockSet(k.content, v, "blocksets.${k.content}")
            }
        } ?: emptyMap()
        return Template(
            name = m.string("name", "") ?: "",
            sheet = m.optional("sheet")?.let { sheet(it, "sheet") } ?: SheetSpec(),
            root = m.optional("root")?.let { attach(it, "root") } ?: AttachSpec.single(self = "topLeft", to = "sheet.topLeft"),
            blocksets = blocksets,
            blocks = blocks(m.required("blocks", ""), "blocks")
        )
    }

    private fun sheet(node: YamlNode, path: String): SheetSpec {
        val m = node.asMap(path)
        m.allow(path, "format", "width", "height", "orientation", "margins", "anchors")
        val width = m.optional("width")?.let { plainNumber(it, "$path.width") }
        val height = m.optional("height")?.let { plainNumber(it, "$path.height") }
        val explicit = width != null || height != null
        val format = m.string("format", path)
        return SheetSpec(
            format = format ?: if (explicit) null else "A4",
            width = width,
            height = height,
            orientation = m.optional("orientation")?.let { orientation(it, "$path.orientation") } ?: Orientation.PORTRAIT,
            margins = m.optional("margins")?.let { margins(it, "$path.margins") } ?: Margins(),
            anchors = anchors(m.optional("anchors"), "$path.anchors")
        )
    }

    private fun orientation(node: YamlNode, path: String): Orientation = when (node.scalar(path).lowercase()) {
        "portrait" -> Orientation.PORTRAIT
        "landscape" -> Orientation.LANDSCAPE
        else -> fail(path, node, "expected portrait|landscape")
    }

    private fun margins(node: YamlNode, path: String): Margins {
        val m = node.asMap(path)
        m.allow(path, "top", "right", "bottom", "left")
        fun side(key: String) = m.optional(key)?.let { plainNumber(it, "$path.$key") } ?: 0.0
        return Margins(side("top"), side("right"), side("bottom"), side("left"))
    }

    private fun anchors(node: YamlNode?, path: String): Map<String, Vec> {
        if (node == null) return emptyMap()
        return node.asMap(path).entries.entries.associate { (k, v) -> k.content to vec(v, "$path.${k.content}") }
    }

    private fun vec(node: YamlNode, path: String): Vec {
        val m = node.asMap(path)
        m.allow(path, "x", "y")
        return Vec(
            m.optional("x")?.let { num(it, "$path.x") } ?: Num.Lit(0.0),
            m.optional("y")?.let { num(it, "$path.y") } ?: Num.Lit(0.0)
        )
    }

    private fun size(node: YamlNode, path: String): SizeSpec {
        val m = node.asMap(path)
        m.allow(path, "width", "height")
        return SizeSpec(num(m.required("width", path), "$path.width"), num(m.required("height", path), "$path.height"))
    }

    // Single form `{self, to, offset: {x, y}}` (sugar for both axes) or per-axis `{x: {self, to, offset}, y: {...}}`.
    private fun attach(node: YamlNode, path: String): AttachSpec {
        val m = node.asMap(path)
        if (m.optional("x") != null || m.optional("y") != null) {
            m.allow(path, "x", "y")
            return AttachSpec(
                axisAttach(m.required("x", path), "$path.x"),
                axisAttach(m.required("y", path), "$path.y"),
                perAxis = true
            )
        }
        m.allow(path, "self", "to", "offset")
        return AttachSpec.single(
            self = m.string("self", path) ?: "topLeft",
            to = m.string("to", path) ?: fail(path, node, "missing required field 'to'"),
            offset = m.optional("offset")?.let { vec(it, "$path.offset") } ?: Vec.ZERO
        )
    }

    private fun axisAttach(node: YamlNode, path: String): AxisAttach {
        val m = node.asMap(path)
        m.allow(path, "self", "to", "offset")
        return AxisAttach(
            self = m.string("self", path) ?: "topLeft",
            to = m.string("to", path) ?: fail(path, node, "missing required field 'to'"),
            offset = m.optional("offset")?.let { num(it, "$path.offset") } ?: Num.Lit(0.0)
        )
    }

    private fun blockSet(name: String, node: YamlNode, path: String): BlockSetDef {
        val m = node.asMap(path)
        m.allow(path, "params", "ports", "blocks")
        val declared = LinkedHashMap<String, String?>()
        m.optional("params")?.asMap("$path.params")?.entries?.forEach { (k, v) ->
            declared[k.content] = if (v is YamlNull) null else v.scalar("$path.params.${k.content}")
        }
        val ports = m.optional("ports")?.asMap("$path.ports")?.entries?.entries?.associate { (k, v) ->
            k.content to v.scalar("$path.ports.${k.content}")
        } ?: emptyMap()
        val saved = params
        params = declared.keys
        try {
            return BlockSetDef(name, declared, ports, blocks(m.required("blocks", path), "$path.blocks"))
        } finally {
            params = saved
        }
    }

    private fun blocks(node: YamlNode, path: String): List<BlockSpec> =
        node.asList(path).items.mapIndexed { i, n -> block(n, "$path[$i]") }

    private fun block(node: YamlNode, path: String): BlockSpec {
        val m = node.asMap(path)
        val id = m.string("id", path) ?: fail(path, node, "missing required field 'id'")
        val type = if (m.optional("use") != null) "blockset" else m.string("type", path)
            ?: fail(path, node, "missing required field 'type'")
        val common = Common(
            anchors(m.optional("anchors"), "$path.anchors"),
            m.optional("attach")?.let { attach(it, "$path.attach") },
            m.optional("when")?.let { selector(it, "$path.when") } ?: PageSelector.ALL,
            m.optional("reserves")?.let { bool(it, "$path.reserves") } ?: false
        )
        return when (type) {
            "frame" -> {
                m.allow(path, COMMON_KEYS + setOf("size", "thickness"))
                FrameBlock(
                    id, size(m.required("size", path), "$path.size"),
                    m.optional("thickness")?.let { thickness(it, "$path.thickness") } ?: Num.Lit(LineWeight.THICK.mm),
                    common.anchors, common.attach, common.visibleOn, common.reserves
                )
            }
            "rect" -> {
                m.allow(path, COMMON_KEYS + setOf("size", "thickness"))
                RectBlock(
                    id, size(m.required("size", path), "$path.size"),
                    m.optional("thickness")?.let { thickness(it, "$path.thickness") } ?: Num.Lit(LineWeight.THIN.mm),
                    common.anchors, common.attach, common.visibleOn, common.reserves
                )
            }
            "text" -> {
                m.allow(path, COMMON_KEYS + setOf("size", "text", "bind", "format", "optional", "align", "rotate", "fontSize"))
                TextBlock(
                    id, size(m.required("size", path), "$path.size"),
                    text = m.string("text", path), bind = m.string("bind", path),
                    format = m.optional("format")?.let { format(it, "$path.format") },
                    optional = m.optional("optional")?.let { bool(it, "$path.optional") } ?: false,
                    align = m.optional("align")?.let { align(it, "$path.align") } ?: TextAlign.LEFT,
                    rotate = m.optional("rotate")?.let { rotate(it, "$path.rotate") } ?: 0,
                    fontSize = m.optional("fontSize")?.let { num(it, "$path.fontSize") },
                    anchors = common.anchors, attach = common.attach, visibleOn = common.visibleOn,
                    reserves = common.reserves
                )
            }
            "flow" -> {
                m.allow(path, COMMON_KEYS + setOf("size", "table"))
                FlowBlock(
                    id, m.optional("size")?.let { size(it, "$path.size") },
                    m.optional("table")?.let { flowTable(it, "$path.table") },
                    common.anchors, common.attach, common.visibleOn, common.reserves
                )
            }
            "table" -> {
                m.allow(path, COMMON_KEYS + setOf("columns", "rows", "rotate", "borders"))
                TableBlock(
                    id,
                    m.required("columns", path).asList("$path.columns").items.mapIndexed { i, n -> num(n, "$path.columns[$i]") },
                    m.required("rows", path).asList("$path.rows").items.mapIndexed { i, n -> row(n, "$path.rows[$i]") },
                    rotate = m.optional("rotate")?.let { rotate(it, "$path.rotate") } ?: 0,
                    borders = m.optional("borders")?.let { borders(it, "$path.borders") },
                    anchors = common.anchors, attach = common.attach, visibleOn = common.visibleOn,
                    reserves = common.reserves
                )
            }
            "blockset" -> {
                m.allow(path, COMMON_KEYS + setOf("use", "args"))
                val args = m.optional("args")?.asMap("$path.args")?.entries?.entries?.associate { (k, v) ->
                    k.content to v.scalar("$path.args.${k.content}").also { checkParamRefs(it, v, "$path.args.${k.content}") }
                } ?: emptyMap()
                BlockSetInstance(
                    id, m.string("use", path)!!, args,
                    common.anchors, common.attach, common.visibleOn, common.reserves
                )
            }
            else -> fail("$path.type", m.optional("type") ?: node, "unknown block type '$type' (frame|rect|text|table|flow|blockset)")
        }
    }

    private class Common(
        val anchors: Map<String, Vec>,
        val attach: AttachSpec?,
        val visibleOn: PageSelector,
        val reserves: Boolean
    )

    private fun flowTable(node: YamlNode, path: String): FlowTableSpec {
        val m = node.asMap(path)
        m.allow(path, "rowHeight", "columns", "header", "groupTitle", "row", "fill", "keep", "styles", "where", "sortBy", "groupBy", "computed", "totals", "lines")
        val rowPath = "$path.row"
        val row = m.required("row", path).asMap(rowPath)
        row.allow(rowPath, "cells")
        return FlowTableSpec(
            rowHeight = plainNumber(m.required("rowHeight", path), "$path.rowHeight"),
            columns = m.required("columns", path).asList("$path.columns").items.mapIndexed { i, n -> flowColumn(n, "$path.columns[$i]") },
            header = m.optional("header")?.let { flowHeader(it, "$path.header") },
            groupTitle = m.optional("groupTitle")?.let { flowGroupTitle(it, "$path.groupTitle") },
            rowCells = cellMap(row.required("cells", rowPath), "$rowPath.cells", ::flowRowCell),
            fill = m.optional("fill")?.let { n ->
                when (n.scalar("$path.fill").lowercase()) {
                    "blank" -> FlowFill.BLANK
                    "none" -> FlowFill.NONE
                    else -> fail("$path.fill", n, "expected blank|none")
                }
            } ?: FlowFill.NONE,
            keep = m.optional("keep")?.let { n ->
                val k = n.asMap("$path.keep")
                k.allow("$path.keep", "titleChain")
                FlowKeep(k.optional("titleChain")?.let { bool(it, "$path.keep.titleChain") } ?: true)
            } ?: FlowKeep(),
            styles = m.optional("styles")?.asMap("$path.styles")?.entries?.entries?.associate { (k, v) ->
                k.content to flowStyle(v, "$path.styles.${k.content}")
            } ?: emptyMap(),
            where = m.optional("where")?.let { predicate(it, "$path.where") },
            sortBy = m.optional("sortBy")?.let { n ->
                n.asList("$path.sortBy").items.mapIndexed { i, k -> flowSort(k, "$path.sortBy[$i]") }
            } ?: emptyList(),
            groupBy = m.optional("groupBy")?.let { flowGroupBy(it, "$path.groupBy") },
            computed = m.optional("computed")?.let { n ->
                n.asMap("$path.computed").entries.entries.associate { (k, v) -> k.content to flowComputed(v, "$path.computed.${k.content}") }
            } ?: emptyMap(),
            totals = m.optional("totals")?.asList("$path.totals")?.items?.mapIndexed { i, n -> flowTotal(n, "$path.totals[$i]") } ?: emptyList(),
            lines = m.optional("lines")?.let { flowLines(it, "$path.lines") }
        )
    }

    // `alias: baseName` or `alias: {base, size, bold, italic, underline}`, see FlowStyle. Values are checked by the
    // validator (base is a known style, size > 0).
    private fun flowStyle(node: YamlNode, path: String): FlowStyle {
        if (node.unwrap() !is YamlMap) return FlowStyle(node.scalar(path))
        val m = node.asMap(path)
        m.allow(path, "base", "size", "bold", "italic", "underline")
        return FlowStyle(
            base = m.required("base", path).scalar("$path.base"),
            size = m.optional("size")?.let { n ->
                n.scalar("$path.size").toDoubleOrNull() ?: fail("$path.size", n, "expected number (font size, mm)")
            },
            bold = m.optional("bold")?.let { bool(it, "$path.bold") },
            italic = m.optional("italic")?.let { bool(it, "$path.italic") },
            underline = m.optional("underline")?.let { bool(it, "$path.underline") },
            asObject = true
        )
    }

    // `{start, scope: table|page, fill}`, see FlowLines.
    private fun flowLines(node: YamlNode, path: String): FlowLines {
        val m = node.asMap(path)
        m.allow(path, "start", "scope", "fill")
        return FlowLines(
            start = m.optional("start")?.let { long(it, "$path.start") } ?: 1,
            scope = m.optional("scope")?.let { n ->
                LinesScope.entries.firstOrNull { it.name == n.scalar("$path.scope").uppercase() } ?: fail("$path.scope", n, "expected table|page")
            } ?: LinesScope.TABLE,
            fill = m.optional("fill")?.let { bool(it, "$path.fill") } ?: false
        )
    }

    // `{id, scope: group|table, agg: sum|count|min|max|avg, field, label, labelColumn, valueColumn, format, style,
    // where, skipEmpty, scale, rounding}`; which keys fit which `agg` is the validator's / contract's job.
    private fun flowTotal(node: YamlNode, path: String): FlowTotal {
        val m = node.asMap(path)
        m.allow(path, "id", "scope", "agg", "field", "label", "labelColumn", "valueColumn", "format", "style", "where", "skipEmpty", "scale", "rounding")
        fun text(key: String) = m.string(key, path) ?: fail(path, node, "missing required field '$key'")
        val scopeNode = m.required("scope", path)
        val aggNode = m.required("agg", path)
        return FlowTotal(
            id = text("id"),
            scope = TotalScope.entries.firstOrNull { it.name == scopeNode.scalar("$path.scope").uppercase() } ?: fail("$path.scope", scopeNode, "expected group|table"),
            agg = TotalAgg.byKey(aggNode.scalar("$path.agg")) ?: fail("$path.agg", aggNode, "expected ${TotalAgg.entries.joinToString("|") { it.key }}"),
            field = m.string("field", path),
            label = text("label"),
            labelColumn = text("labelColumn"),
            valueColumn = text("valueColumn"),
            format = m.optional("format")?.let { format(it, "$path.format") },
            style = m.string("style", path),
            where = m.optional("where")?.let { predicate(it, "$path.where") },
            skipEmpty = m.optional("skipEmpty")?.let { bool(it, "$path.skipEmpty") } ?: true,
            scale = m.optional("scale")?.let { integer(it, "$path.scale") },
            rounding = m.optional("rounding")?.let { rounding(it, "$path.rounding") }
        )
    }

    // {field, order: asc|desc, nulls: first|last}
    private fun flowSort(node: YamlNode, path: String): FlowSort {
        val m = node.asMap(path)
        m.allow(path, "field", "order", "nulls")
        return FlowSort(
            field = m.string("field", path) ?: fail(path, node, "missing required field 'field'"),
            order = m.optional("order")?.let { n ->
                SortOrder.entries.firstOrNull { it.name == n.scalar("$path.order").uppercase() } ?: fail("$path.order", n, "expected asc|desc")
            } ?: SortOrder.ASC,
            nulls = m.optional("nulls")?.let { n ->
                NullsOrder.entries.firstOrNull { it.name == n.scalar("$path.nulls").uppercase() } ?: fail("$path.nulls", n, "expected first|last")
            } ?: NullsOrder.LAST
        )
    }

    private fun flowGroupBy(node: YamlNode, path: String): FlowGroupBy {
        val m = node.asMap(path)
        m.allow(path, "field", "order", "titles", "skipEmpty", "omit")
        fun names(key: String) = m.optional(key)?.asList("$path.$key")?.items?.mapIndexed { i, n -> n.scalar("$path.$key[$i]") }
        return FlowGroupBy(
            field = m.string("field", path) ?: fail(path, node, "missing required field 'field'"),
            order = names("order") ?: fail(path, node, "missing required field 'order'"),
            titles = m.optional("titles")?.asMap("$path.titles")?.entries?.entries?.associate { (k, v) ->
                k.content to v.scalar("$path.titles.${k.content}")
            } ?: emptyMap(),
            skipEmpty = m.optional("skipEmpty")?.let { bool(it, "$path.skipEmpty") } ?: true,
            omit = names("omit") ?: emptyList()
        )
    }

    // `computed.<name>`: a closed set of operations: `sequence: {scope, start, step}` or arithmetic, one key
    // `multiply|add|subtract|divide: [operand, ...]` with optional `scale` / `rounding` beside it.
    private fun flowComputed(node: YamlNode, path: String): FlowComputed {
        val m = node.asMap(path)
        val ops = ArithOp.entries.map { it.key }
        m.allow(path, (listOf("sequence") + ops + listOf("scale", "rounding")).toSet())
        val given = (listOf("sequence") + ops).filter { m.optional(it) != null }
        if (given.size != 1) fail(path, node, "expected exactly one of ${(listOf("sequence") + ops).joinToString("|")}, got ${given.ifEmpty { listOf("none") }.joinToString()}")
        val key = given.single()
        if (key == "sequence") {
            if (m.optional("scale") != null || m.optional("rounding") != null) fail(path, node, "'scale' / 'rounding' apply to arithmetic, not to 'sequence'")
            val seq = m.required("sequence", path).asMap("$path.sequence")
            val sp = "$path.sequence"
            seq.allow(sp, "scope", "start", "step")
            return FlowComputed.Sequence(
                scope = seq.optional("scope")?.let { n ->
                    SequenceScope.entries.firstOrNull { it.name == n.scalar("$sp.scope").uppercase() } ?: fail("$sp.scope", n, "expected table|group")
                } ?: SequenceScope.TABLE,
                start = seq.optional("start")?.let { long(it, "$sp.start") } ?: 1,
                step = seq.optional("step")?.let { long(it, "$sp.step") } ?: 1
            )
        }
        return FlowComputed.Arithmetic(
            op = ArithOp.byKey(key)!!,
            operands = m.required(key, path).asList("$path.$key").items.mapIndexed { i, n ->
                val text = n.scalar("$path.$key[$i]")
                if (NUMBER_LITERAL.matches(text)) Operand.Literal(text) else Operand.Field(text)
            },
            scale = m.optional("scale")?.let { integer(it, "$path.scale") },
            rounding = m.optional("rounding")?.let { rounding(it, "$path.rounding") }
        )
    }

    // Closed predicate: `{field, eq|ne|in|isNull|notNull}` or exactly one of `{and: [..]}`, `{or: [..]}`, `{not: p}`.
    private fun predicate(node: YamlNode, path: String): Predicate {
        val m = node.asMap(path)
        m.allow(path, "field", "eq", "ne", "in", "isNull", "notNull", "and", "or", "not")
        val keys = m.entries.keys.map { it.content }
        val combinators = keys.filter { it in setOf("and", "or", "not") }
        if (combinators.isNotEmpty()) {
            if (keys.size != 1) fail(path, node, "'${combinators.first()}' must be the only key of a predicate, got ${keys.joinToString()}")
            fun items(key: String) = m.required(key, path).asList("$path.$key").items.mapIndexed { i, n -> predicate(n, "$path.$key[$i]") }
            return when (combinators.single()) {
                "and" -> Predicate.And(items("and"))
                "or" -> Predicate.Or(items("or"))
                else -> Predicate.Not(predicate(m.required("not", path), "$path.not"))
            }
        }
        val field = m.string("field", path) ?: fail(path, node, "missing required field 'field' (or one of and|or|not)")
        val ops = keys.filter { it != "field" }
        if (ops.size != 1) fail(path, node, "expected exactly one of eq|ne|in|isNull|notNull next to 'field', got ${ops.ifEmpty { listOf("none") }.joinToString()}")
        return when (val op = ops.single()) {
            "eq" -> Predicate.Eq(field, m.required("eq", path).scalar("$path.eq"))
            "ne" -> Predicate.Ne(field, m.required("ne", path).scalar("$path.ne"))
            "in" -> Predicate.In(field, m.required("in", path).asList("$path.in").items.mapIndexed { i, n -> n.scalar("$path.in[$i]") })
            else -> {
                val flag = m.required(op, path)
                if (!bool(flag, "$path.$op")) fail("$path.$op", flag, "expected true (use ${if (op == "isNull") "notNull" else "isNull"} for the opposite)")
                if (op == "isNull") Predicate.IsNull(field) else Predicate.NotNull(field)
            }
        }
    }

    private fun flowColumn(node: YamlNode, path: String): FlowColumn {
        val m = node.asMap(path)
        m.allow(path, "id", "width", "stick", "align")
        return FlowColumn(
            id = m.string("id", path) ?: fail(path, node, "missing required field 'id'"),
            width = plainNumber(m.required("width", path), "$path.width"),
            stick = m.optional("stick")?.let { n -> FlowStick.byKey(n.scalar("$path.stick")) ?: fail("$path.stick", n, "expected first|last|none") }
                ?: FlowStick.NONE,
            align = m.optional("align")?.let { flowAlign(it, "$path.align") } ?: TextAlign.LEFT
        )
    }

    private fun flowHeader(node: YamlNode, path: String): FlowHeader {
        val m = node.asMap(path)
        m.allow(path, "height", "repeat", "cells", "rows")
        val repeat = m.optional("repeat")?.let { bool(it, "$path.repeat") } ?: true
        val rowsNode = m.optional("rows")
        if (rowsNode != null) {
            val single = listOf("height", "cells").filter { m.optional(it) != null }
            if (single.isNotEmpty()) fail(path, node, "'rows' excludes ${single.joinToString { "'$it'" }} (use 'rows' or 'height' + 'cells')")
            val rows = rowsNode.asList("$path.rows").items.mapIndexed { i, n -> flowHeaderRow(n, "$path.rows[$i]") }
            return FlowHeader(height = rows.sumOf { it.height }, repeat = repeat, cells = emptyMap(), rows = rows)
        }
        if (m.optional("height") == null && m.optional("cells") == null) fail(path, node, "expected 'rows' or 'height' + 'cells'")
        return FlowHeader(
            height = plainNumber(m.required("height", path), "$path.height"),
            repeat = repeat,
            cells = cellMap(m.required("cells", path), "$path.cells") { n, p -> flowHeaderCell(n, p) }
        )
    }

    private fun flowHeaderRow(node: YamlNode, path: String): FlowHeaderRow {
        val m = node.asMap(path)
        m.allow(path, "height", "cells")
        return FlowHeaderRow(
            height = plainNumber(m.required("height", path), "$path.height"),
            cells = m.required("cells", path).asList("$path.cells").items.mapIndexed { i, n -> flowHeaderGridCell(n, "$path.cells[$i]") }
        )
    }

    private fun flowGroupTitle(node: YamlNode, path: String): FlowGroupTitle {
        val m = node.asMap(path)
        m.allow(path, "column", "style", "align", "spacerBefore", "spacerAfter")
        return FlowGroupTitle(
            column = m.string("column", path) ?: fail(path, node, "missing required field 'column'"),
            style = m.string("style", path),
            align = m.optional("align")?.let { flowAlign(it, "$path.align") } ?: TextAlign.CENTER,
            spacerBefore = m.optional("spacerBefore")?.let { integer(it, "$path.spacerBefore") } ?: 0,
            spacerAfter = m.optional("spacerAfter")?.let { integer(it, "$path.spacerAfter") } ?: 0
        )
    }

    // Cells keyed by column id; `~` (null) = empty cell, a scalar = literal text.
    private fun <T> cellMap(node: YamlNode, path: String, cell: (YamlNode, String) -> T): Map<String, T> =
        node.asMap(path).entries.entries.associate { (k, v) -> k.content to cell(v, "$path.${k.content}") }

    // Cell of the `rows` form: always an object (no scalar / `~` shorthand), may carry `span` / `rowSpan`.
    private fun flowHeaderGridCell(node: YamlNode, path: String): FlowHeaderCell {
        node.asMap(path)
        return flowHeaderCell(node, path, grid = true)
    }

    private fun flowHeaderCell(node: YamlNode, path: String, grid: Boolean = false): FlowHeaderCell {
        if (node is YamlNull) return FlowHeaderCell("")
        if (node is YamlScalar) return FlowHeaderCell(node.content)
        val m = node.asMap(path)
        if (grid) m.allow(path, "text", "lines", "rotate", "align", "style", "span", "rowSpan")
        else m.allow(path, "text", "lines", "rotate", "align", "style")
        return FlowHeaderCell(
            span = m.optional("span")?.let { integer(it, "$path.span") } ?: 1,
            rowSpan = m.optional("rowSpan")?.let { integer(it, "$path.rowSpan") } ?: 1,
            text = m.string("text", path) ?: fail(path, node, "missing required field 'text'"),
            lines = m.optional("lines")?.asList("$path.lines")?.items?.mapIndexed { i, n -> n.scalar("$path.lines[$i]") },
            rotate = m.optional("rotate")?.let { rotate(it, "$path.rotate") } ?: 0,
            align = m.optional("align")?.let { flowAlign(it, "$path.align") } ?: TextAlign.CENTER,
            style = m.string("style", path)
        )
    }

    private fun flowRowCell(node: YamlNode, path: String): FlowRowCell {
        if (node is YamlNull) return FlowRowCell()
        if (node is YamlScalar) return FlowRowCell(text = node.content)
        val m = node.asMap(path)
        m.allow(path, "text", "bind", "format", "optional", "align", "style", "cases")
        return FlowRowCell(
            text = m.string("text", path), bind = m.string("bind", path),
            format = m.optional("format")?.let { format(it, "$path.format") },
            optional = m.optional("optional")?.let { bool(it, "$path.optional") } ?: false,
            align = m.optional("align")?.let { flowAlign(it, "$path.align") },
            style = m.string("style", path),
            cases = m.optional("cases")?.asList("$path.cases")?.items?.mapIndexed { i, n -> flowCase(n, "$path.cases[$i]") } ?: emptyList()
        )
    }

    private fun flowCase(node: YamlNode, path: String): FlowCase {
        val m = node.asMap(path)
        m.allow(path, "where", "text", "bind", "format", "optional")
        return FlowCase(
            where = predicate(m.required("where", path), "$path.where"),
            text = m.string("text", path), bind = m.string("bind", path),
            format = m.optional("format")?.let { format(it, "$path.format") },
            optional = m.optional("optional")?.let { bool(it, "$path.optional") } ?: false
        )
    }

    // Flow table cells are drawn by the engine's bordered rows: left and center only.
    private fun flowAlign(node: YamlNode, path: String): TextAlign = when (node.scalar(path).lowercase()) {
        "left" -> TextAlign.LEFT
        "center" -> TextAlign.CENTER
        else -> fail(path, node, "expected left|center")
    }

    private fun row(node: YamlNode, path: String): RowSpec {
        val m = node.asMap(path)
        if (m.optional("repeat") != null) {
            m.allow(path, "repeat")
            val rp = "$path.repeat"
            val r = m.required("repeat", path).asMap(rp)
            r.allow(rp, "count", "from", "row")
            return RepeatRows(
                count = r.optional("count")?.let { integer(it, "$rp.count") },
                from = r.string("from", rp),
                row = fixedRow(r.required("row", rp), "$rp.row")
            )
        }
        return fixedRow(node, path)
    }

    private fun fixedRow(node: YamlNode, path: String): FixedRow {
        val m = node.asMap(path)
        m.allow(path, "height", "cells")
        return FixedRow(
            num(m.required("height", path), "$path.height"),
            m.required("cells", path).asList("$path.cells").items.mapIndexed { i, n -> cell(n, "$path.cells[$i]") }
        )
    }

    // Cell shorthand: scalar = text, null = empty cell.
    private fun cell(node: YamlNode, path: String): CellSpec {
        if (node is YamlNull) return CellSpec()
        if (node is YamlScalar) return CellSpec(text = node.content.also { checkParamRefs(it, node, path) })
        val m = node.asMap(path)
        m.allow(path, "text", "bind", "format", "optional", "span", "rowSpan", "rotate", "align", "fontSize", "style", "borders")
        return CellSpec(
            text = m.string("text", path), bind = m.string("bind", path),
            format = m.optional("format")?.let { format(it, "$path.format") },
            optional = m.optional("optional")?.let { bool(it, "$path.optional") } ?: false,
            span = m.optional("span")?.let { integer(it, "$path.span") } ?: 1,
            rowSpan = m.optional("rowSpan")?.let { integer(it, "$path.rowSpan") } ?: 1,
            style = m.string("style", path),
            borders = m.optional("borders")?.let { borders(it, "$path.borders") },
            rotate = m.optional("rotate")?.let { rotate(it, "$path.rotate") } ?: 0,
            align = m.optional("align")?.let { align(it, "$path.align") } ?: TextAlign.LEFT,
            fontSize = m.optional("fontSize")?.let { num(it, "$path.fontSize") }
        )
    }

    // `{pattern, locale, rounding}`; which keys fit is decided against the bound type by TemplateContract.
    private fun format(node: YamlNode, path: String): FormatSpec {
        val m = node.asMap(path)
        m.allow(path, "pattern", "locale", "rounding")
        val locale = m.optional("locale")?.let { n ->
            n.scalar("$path.locale").also {
                if (it !in FORMAT_LOCALES) fail("$path.locale", n, "expected ${FORMAT_LOCALES.keys.joinToString("|")}")
            }
        }
        val rounding = m.optional("rounding")?.let { rounding(it, "$path.rounding") }
        return FormatSpec(m.string("pattern", path), locale, rounding)
    }

    private fun rounding(node: YamlNode, path: String): RoundingMode =
        FORMAT_ROUNDINGS.firstOrNull { it.name == node.scalar(path).uppercase() }
            ?: fail(path, node, "expected ${FORMAT_ROUNDINGS.joinToString("|") { it.name }}")

    // `thin` / `thick` or a number in mm.
    private fun thickness(node: YamlNode, path: String): Num {
        val text = node.scalar(path)
        LineWeight.byKey(text)?.takeIf { it != LineWeight.NONE }?.let { return Num.Lit(it.mm) }
        return num(node, path)
    }

    private fun weight(node: YamlNode, path: String): LineWeight =
        LineWeight.byKey(node.scalar(path)) ?: fail(path, node, "expected none|thin|thick")

    // Scalar = all four sides; map = listed sides only (the rest inherit).
    private fun borders(node: YamlNode, path: String): BorderSpec {
        if (node.unwrap() is YamlScalar) return BorderSpec.all(weight(node, path))
        val m = node.asMap(path)
        m.allow(path, "top", "right", "bottom", "left")
        fun side(key: String) = m.optional(key)?.let { weight(it, "$path.$key") }
        return BorderSpec(side("top"), side("right"), side("bottom"), side("left"))
    }

    private fun selector(node: YamlNode, path: String): PageSelector = when (node.scalar(path).lowercase()) {
        "first" -> PageSelector.FIRST
        "rest" -> PageSelector.REST
        "all" -> PageSelector.ALL
        else -> fail(path, node, "expected first|rest|all")
    }

    private fun align(node: YamlNode, path: String): TextAlign = when (node.scalar(path).lowercase()) {
        "left" -> TextAlign.LEFT
        "center" -> TextAlign.CENTER
        "right" -> TextAlign.RIGHT
        else -> fail(path, node, "expected left|center|right")
    }

    private fun rotate(node: YamlNode, path: String): Int {
        val v = integer(node, path)
        if (v !in setOf(0, 90, 270)) fail(path, node, "expected 0|90|270")
        return v
    }

    private fun bool(node: YamlNode, path: String): Boolean = when (node.scalar(path).lowercase()) {
        "true" -> true
        "false" -> false
        else -> fail(path, node, "expected true|false")
    }

    private fun integer(node: YamlNode, path: String): Int =
        node.scalar(path).toIntOrNull() ?: fail(path, node, "expected integer")

    private fun long(node: YamlNode, path: String): Long =
        node.scalar(path).toLongOrNull() ?: fail(path, node, "expected integer")

    private fun plainNumber(node: YamlNode, path: String): Double =
        node.scalar(path).toDoubleOrNull() ?: fail(path, node, "expected number (mm)")

    private fun num(node: YamlNode, path: String): Num {
        val text = node.scalar(path)
        text.toDoubleOrNull()?.let { return Num.Lit(it) }
        val ref = PARAM_REF.matchEntire(text) ?: fail(path, node, "expected number (mm) or \${param.x}, got '$text'")
        checkParamRefs(text, node, path)
        return Num.Ref(ref.groupValues[1])
    }

    private fun checkParamRefs(text: String, node: YamlNode, path: String) {
        PARAM_REF.findAll(text).forEach {
            val name = it.groupValues[1]
            if (name !in params) fail(path, node, "unknown param '$name'" + if (params.isEmpty()) " (params exist only inside blocksets)" else "")
        }
    }

    // --- node helpers ---

    private fun YamlNode.asMap(path: String): YamlMap = (unwrap() as? YamlMap) ?: fail(path, this, "expected mapping")

    private fun YamlNode.asList(path: String): YamlList = (unwrap() as? YamlList) ?: fail(path, this, "expected list")

    private fun YamlNode.scalar(path: String): String =
        (unwrap() as? YamlScalar)?.content ?: fail(path, this, "expected scalar value")

    private fun YamlNode.unwrap(): YamlNode = if (this is YamlTaggedNode) innerNode else this

    // Absent or null -> null.
    private fun YamlMap.optional(key: String): YamlNode? =
        entries.entries.firstOrNull { it.key.content == key }?.value?.takeUnless { it is YamlNull }

    private fun YamlMap.required(key: String, path: String): YamlNode =
        optional(key) ?: fail(path, this, "missing required field '$key'")

    private fun YamlMap.string(key: String, path: String): String? =
        optional(key)?.let { n ->
            n.scalar(join(path, key)).also { checkParamRefs(it, n, join(path, key)) }
        }

    private fun YamlMap.allow(path: String, vararg keys: String) = allow(path, keys.toSet())

    private fun YamlMap.allow(path: String, keys: Set<String>) {
        entries.keys.firstOrNull { it.content !in keys }?.let {
            fail(join(path, it.content), it, "unknown field '${it.content}' (allowed: ${keys.sorted().joinToString()})")
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun fail(path: String, node: YamlNode?, message: String): Nothing =
        throw TemplateException(path, if (node != null) "$message (line ${node.location.line})" else message)
}
