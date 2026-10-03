package dev.reportgenerator.template

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.geometry.placeOrigin

// Template -> absolute geometry. Blocks are placed in attach-DAG order (sheet is the root):
// origin = target anchor + offset - own `self` anchor (geometry placeOrigin), per axis: x from the x attach,
// y from the y attach. Blockset instances are resolved in their own local coordinate space
// (origin = `self.topLeft`) and then translated.
object TemplateResolver {
    fun resolve(template: Template, kind: PageKind): ResolvedTemplate {
        TemplateValidator.require(template)
        val sheet = resolveSheet(template.sheet)
        val ctx = Ctx(template.blocksets)
        val rootAnchors = sheet.anchors
        val placed = ctx.scope(template.blocks, "sheet", rootAnchors, template.root, ParamEnv(emptyMap()), "").blocks

        val region = flowRegion(sheet.contentRect, placed.filter { it.visibleOn.matches(kind) })

        val fillIds = template.blocks.filter { it is FlowBlock && it.size == null }.map { it.id }.toSet()
        val blocks = placed
            .map { if (it.id in fillIds) it.copy(rect = region, anchors = region.standardAnchors()) else it }
            .filter { it.visibleOn.matches(kind) }
        return ResolvedTemplate(template.name, kind, sheet, blocks, region)
    }

    fun flowRegion(template: Template, kind: PageKind): Rect = resolve(template, kind).flowRegion

    // Content area minus blocks flagged `reserves`. Same rule as LayoutEngine's old contentBottom(): only
    // blocks that horizontally overlap the content column count (a block in the margin gutter doesn't) and
    // sit at or below the content top; the nearest top edge wins (union-bounded, not sum). Only the bottom of
    // the area is reserved. TODO (possible follow-up): a symmetric top case for blocks hanging from the top
    // edge - not implemented on purpose, the engine has none, and without it such a block collapses the region.
    fun flowRegion(content: Rect, visible: List<ResolvedBlock>): Rect =
        flowRegionFor(content, visible.filter { it.reserves }.map { it.rect })

    // Same on bare rects (callers that already filtered by `reserves`).
    fun flowRegionFor(content: Rect, reserved: List<Rect>): Rect {
        val bottom = reserved
            .filter { it.left < content.right && it.right > content.left && it.top >= content.top }
            .fold(content.bottom) { acc, r -> minOf(acc, r.top) }
        return Rect(content.left, content.top, content.width, bottom - content.top)
    }

    private fun resolveSheet(spec: SheetSpec): ResolvedSheet {
        val format = SheetFormats.resolve(spec)
        val rect = Rect(Length.ZERO, Length.ZERO, format.width, format.height)
        val m = spec.margins
        val content = Rect(
            m.left.mm, m.top.mm,
            format.width - m.left.mm - m.right.mm,
            format.height - m.top.mm - m.bottom.mm
        )
        val anchors = LinkedHashMap<String, Point>()
        anchors += rect.standardAnchors()
        anchors["contentTopLeft"] = Point(content.left, content.top)
        anchors["contentTopRight"] = Point(content.right, content.top)
        anchors["contentBottomLeft"] = Point(content.left, content.bottom)
        anchors["contentBottomRight"] = Point(content.right, content.bottom)
        val env = ParamEnv(emptyMap())
        for ((name, v) in spec.anchors) {
            anchors[name] = Point(rect.left + env.mm(v.x, "sheet.anchors.$name.x"), rect.top + env.mm(v.y, "sheet.anchors.$name.y"))
        }
        return ResolvedSheet(format, rect, content, anchors)
    }
}

internal class ParamEnv(private val values: Map<String, String>) {
    fun mm(n: Num, path: String): Length = number(n, path).mm

    fun number(n: Num, path: String): Double = when (n) {
        is Num.Lit -> n.value
        is Num.Ref -> values[n.param]?.toDoubleOrNull()
            ?: throw TemplateException(path, "param '${n.param}' is not a number: '${values[n.param]}'")
    }

    fun text(s: String): String = PARAM_REF.replace(s) { values[it.groupValues[1]] ?: it.value }
}

private class ScopeResult(val blocks: List<ResolvedBlock>, val anchors: Map<String, Map<String, Point>>)

private class Local(
    val rect: Rect,
    val anchors: Map<String, Point>,
    val cells: List<ResolvedCell> = emptyList(),
    val children: List<ResolvedBlock> = emptyList()
)

private class Ctx(val defs: Map<String, BlockSetDef>) {
    fun scope(
        blocks: List<BlockSpec>,
        rootName: String,
        rootAnchors: Map<String, Point>,
        defaultAttach: AttachSpec,
        env: ParamEnv,
        prefix: String
    ): ScopeResult {
        val order = (topoSort(blocks, defaultAttach) as Topo.Order).blocks
        val anchorsById = LinkedHashMap<String, Map<String, Point>>()
        anchorsById[rootName] = rootAnchors
        val out = ArrayList<ResolvedBlock>()

        for (spec in order) {
            val path = prefix + spec.id
            val attach = attachOf(spec, defaultAttach)
            val local = local(spec, env, prefix)
            // x and y are independent; each uses only its own coordinate of target / self anchor
            fun axis(a: AxisAttach): Triple<Point, Point, Length> {
                val ref = parseAnchorRef(a.to)!!
                val target = anchorsById.getValue(ref.blockId)[normalizeAnchorName(ref.anchor)]
                    ?: throw TemplateException(path, "unresolved anchor '${a.to}'")
                return Triple(target, local.anchors.getValue(normalizeAnchorName(a.self)), env.mm(a.offset, "$path.attach.offset"))
            }
            val (tx, sx, ox) = axis(attach.x)
            val (ty, sy, oy) = if (attach.perAxis) axis(attach.y) else Triple(tx, sx, env.mm(attach.y.offset, "$path.attach.offset"))
            val origin = Point(
                placeOrigin(tx, sx, Point(ox, Length.ZERO)).x,
                placeOrigin(ty, sy, Point(Length.ZERO, oy)).y
            )

            val block = resolvedBlock(spec, prefix + spec.id, local, env)
            val moved = block.translated(origin.x, origin.y)
            anchorsById[spec.id] = moved.anchors
            out += moved
            for (child in local.children) {
                val visible = spec.visibleOn.and(child.visibleOn) ?: continue
                out += child.translated(origin.x, origin.y).copy(visibleOn = visible)
            }
        }
        return ScopeResult(out, anchorsById)
    }

    private fun local(spec: BlockSpec, env: ParamEnv, prefix: String): Local {
        val path = prefix + spec.id
        val local = when (spec) {
            is FrameBlock -> box(spec.size, env, path)
            is RectBlock -> box(spec.size, env, path)
            is TextBlock -> box(spec.size, env, path)
            is FlowBlock -> spec.size?.let { box(it, env, path) } ?: Local(Rect(Length.ZERO, Length.ZERO, Length.ZERO, Length.ZERO), emptyMap())
            is TableBlock -> table(spec, env, path)
            is BlockSetInstance -> instance(spec, env, prefix)
        }
        val rect = local.rect
        val anchors = LinkedHashMap<String, Point>()
        anchors += rect.standardAnchors()
        anchors += local.anchors
        // table custom anchors are already mapped through the block rotation in table()
        if (spec !is TableBlock) {
            for ((name, v) in spec.anchors) {
                anchors[name] = Point(rect.left + env.mm(v.x, "$path.anchors.$name.x"), rect.top + env.mm(v.y, "$path.anchors.$name.y"))
            }
        }
        return Local(rect, anchors, local.cells, local.children)
    }

    private fun box(size: SizeSpec, env: ParamEnv, path: String): Local {
        val w = env.mm(size.width, "$path.size.width")
        val h = env.mm(size.height, "$path.size.height")
        if (w <= Length.ZERO || h <= Length.ZERO) throw TemplateException(path, "size must be > 0 (got $w x $h)")
        return Local(Rect(Length.ZERO, Length.ZERO, w, h), emptyMap())
    }

    // The table is laid out unrotated (w x h, own grid), then mapped into the rotated bounding box, whose
    // top-left is the block origin: 90 = counterclockwise ((x,y) -> (y, w-x)), 270 = clockwise
    // ((x,y) -> (h-y, x)). Standard anchors (block and `cell[r,c].*`) are recomputed from the rotated rects
    // (topLeft is always the top-left in sheet axes); col[i].* / row[j].* / custom anchors are points that
    // travel with the block (col[i].left is the point on the table's own top edge at the column's left).
    private fun table(t: TableBlock, env: ParamEnv, path: String): Local {
        val widths = t.columns.mapIndexed { i, c -> env.mm(c, "$path.columns[$i]") }
        val rows = t.expandedRows()
        val heights = rows.mapIndexed { i, r -> env.mm(r.height, "$path.rows[$i].height") }
        if (widths.any { it <= Length.ZERO } || heights.any { it <= Length.ZERO }) {
            throw TemplateException(path, "table column widths and row heights must be > 0")
        }
        val xs = widths.runningFold(Length.ZERO) { acc, w -> acc + w }
        val ys = heights.runningFold(Length.ZERO) { acc, h -> acc + h }
        val w = xs.last()
        val h = ys.last()
        val rot = t.rotate
        fun point(p: Point): Point = when (rot) {
            90 -> Point(p.y, w - p.x)
            270 -> Point(h - p.y, p.x)
            else -> p
        }
        fun rect(r: Rect): Rect = when (rot) {
            90 -> Rect(r.y, w - r.right, r.height, r.width)
            270 -> Rect(h - r.bottom, r.x, r.height, r.width)
            else -> r
        }
        fun borders(b: ResolvedBorders): ResolvedBorders = when (rot) {
            90 -> ResolvedBorders(b.right, b.bottom, b.left, b.top)
            270 -> ResolvedBorders(b.left, b.top, b.right, b.bottom)
            else -> b
        }
        val unrotated = Rect(Length.ZERO, Length.ZERO, w, h)
        val blockRect = rect(unrotated).let { Rect(Length.ZERO, Length.ZERO, it.width, it.height) }

        val anchors = LinkedHashMap<String, Point>()
        // column edges sit on the table's top edge, row edges on its left edge
        widths.indices.forEach { i ->
            anchors["col[$i].left"] = point(Point(xs[i], Length.ZERO))
            anchors["col[$i].right"] = point(Point(xs[i + 1], Length.ZERO))
        }
        heights.indices.forEach { j ->
            anchors["row[$j].top"] = point(Point(Length.ZERO, ys[j]))
            anchors["row[$j].bottom"] = point(Point(Length.ZERO, ys[j + 1]))
        }
        for ((name, v) in t.anchors) {
            anchors[name] = point(Point(env.mm(v.x, "$path.anchors.$name.x"), env.mm(v.y, "$path.anchors.$name.y")))
        }
        val cells = ArrayList<ResolvedCell>()
        for (g in layoutGrid(rows, widths.size).cells) {
            val cell = g.spec
            val endRow = minOf(g.row + cell.rowSpan, rows.size)
            val cellRect = rect(Rect(xs[g.col], ys[g.row], xs[g.col + cell.span] - xs[g.col], ys[endRow] - ys[g.row]))
            anchors += cellRect.standardAnchors("cell[${g.row},${g.col}].")
            val b = cell.borders
            val tb = t.borders
            cells += ResolvedCell(
                g.row, g.col, cell.span, cellRect,
                cell.text?.let(env::text), cell.bind?.let(env::text), (rot + cell.rotate) % 360, cell.align,
                cell.fontSize?.let { env.number(it, "$path.rows[${g.rowIndex}].cells[${g.cellIndex}].fontSize") },
                cell.rowSpan, cell.style,
                borders(
                    ResolvedBorders(
                        b?.top ?: tb?.top ?: LineWeight.THICK,
                        b?.right ?: tb?.right ?: LineWeight.THICK,
                        b?.bottom ?: tb?.bottom ?: LineWeight.THICK,
                        b?.left ?: tb?.left ?: LineWeight.THICK
                    )
                ),
                cell.format, cell.optional
            )
        }
        return Local(blockRect, anchors, cells)
    }

    private fun instance(spec: BlockSetInstance, env: ParamEnv, prefix: String): Local {
        val def = defs.getValue(spec.use)
        val values = LinkedHashMap<String, String>()
        for ((name, default) in def.params) {
            values[name] = spec.args[name]?.let(env::text) ?: default
                ?: throw TemplateException(prefix + spec.id, "missing param '$name'")
        }
        val inner = scope(
            def.blocks, "self", mapOf("topLeft" to Point(Length.ZERO, Length.ZERO)), SELF_ATTACH,
            ParamEnv(values), prefix + spec.id + "/"
        )
        val rects = inner.blocks.map { it.rect }
        val left = rects.minOf { it.left }
        val top = rects.minOf { it.top }
        val rect = Rect(left, top, rects.maxOf { it.right } - left, rects.maxOf { it.bottom } - top)

        val ports = def.ports.mapValues { (port, to) ->
            val ref = parseAnchorRef(to)!!
            inner.anchors.getValue(ref.blockId)[normalizeAnchorName(ref.anchor)]
                ?: throw TemplateException("${prefix + spec.id}.ports.$port", "unresolved anchor '$to'")
        }
        return Local(rect, ports, children = inner.blocks)
    }

    private fun resolvedBlock(spec: BlockSpec, id: String, local: Local, env: ParamEnv): ResolvedBlock {
        val base = ResolvedBlock(
            id = id, type = BlockType.RECT, rect = local.rect, anchors = local.anchors, cells = local.cells,
            visibleOn = spec.visibleOn, reserves = spec.reserves
        )
        return when (spec) {
            is FrameBlock -> base.copy(type = BlockType.FRAME, thickness = env.mm(spec.thickness, "$id.thickness"))
            is RectBlock -> base.copy(type = BlockType.RECT, thickness = env.mm(spec.thickness, "$id.thickness"))
            is TextBlock -> base.copy(
                type = BlockType.TEXT, text = spec.text?.let(env::text), bind = spec.bind?.let(env::text),
                align = spec.align, rotate = spec.rotate, fontSize = spec.fontSize?.let { env.number(it, "$id.fontSize") },
                format = spec.format, optional = spec.optional
            )
            is FlowBlock -> base.copy(type = BlockType.FLOW)
            is TableBlock -> base.copy(type = BlockType.TABLE, rotate = spec.rotate)
            is BlockSetInstance -> base.copy(type = BlockType.BLOCKSET)
        }
    }
}
