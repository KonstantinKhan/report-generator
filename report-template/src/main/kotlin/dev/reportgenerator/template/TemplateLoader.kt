package dev.reportgenerator.template

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
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

    // parse + validate.
    fun load(yaml: String): Template = parse(yaml).also { TemplateValidator.require(it) }

    fun load(path: Path): Template = load(Files.readString(path))
}

internal val PARAM_REF = Regex("""\$\{param\.([A-Za-z_][A-Za-z0-9_]*)}""")
internal val BIND_EXPR = Regex("""\$\{(doc|page|item)\.[A-Za-z_][A-Za-z0-9_.]*}""")
internal val ID_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]*")

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
                m.allow(path, COMMON_KEYS + "size")
                FlowBlock(
                    id, m.optional("size")?.let { size(it, "$path.size") },
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
        val rounding = m.optional("rounding")?.let { n ->
            FORMAT_ROUNDINGS.firstOrNull { it.name == n.scalar("$path.rounding").uppercase() }
                ?: fail("$path.rounding", n, "expected ${FORMAT_ROUNDINGS.joinToString("|") { it.name }}")
        }
        return FormatSpec(m.string("pattern", path), locale, rounding)
    }

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
