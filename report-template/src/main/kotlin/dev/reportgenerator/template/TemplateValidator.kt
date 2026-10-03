package dev.reportgenerator.template

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.mm

// Semantic checks on a parsed Template: ids, attach references (block + anchor existence), cycles,
// `when` compatibility, table shape, flow table structure, blockset params/ports and recursion. Collects all
// errors. `styleNames` = the text style names the consumer knows; when given, flow table style references are
// checked against it (otherwise they stay opaque keys).
object TemplateValidator {
    fun validate(template: Template, styleNames: Set<String>? = null): List<TemplateError> {
        val errors = ArrayList<TemplateError>()
        val sheet = template.sheet
        validateSheet(sheet, errors)
        val flowWidth = if (errors.isEmpty()) flowWidth(sheet) else null

        val rootAnchors = SHEET_ANCHOR_NAMES + sheet.anchors.keys
        val rootAttach = template.root
        for ((axisName, axis) in axisPaths(rootAttach, "root")) {
            val ref = parseAnchorRef(axis.to)
            if (ref == null || ref.blockId != "sheet" || normalizeAnchorName(ref.anchor) !in rootAnchors) {
                errors += TemplateError("$axisName.to", "root must attach to a sheet anchor (e.g. 'sheet.topLeft'), got '${axis.to}'")
            }
            if (normalizeAnchorName(axis.self) !in StdAnchor.keys) {
                errors += TemplateError("$axisName.self", "unknown anchor '${axis.self}'")
            }
        }

        detectRecursion(template.blocksets, errors)
        for ((name, def) in template.blocksets) {
            validateBlockSetDef(name, def, template.blocksets, errors)
        }

        val scope = Scope(
            rootName = "sheet", rootHas = { it in rootAnchors }, prefix = "", defaultAttach = rootAttach,
            params = null, defs = template.blocksets, topLevel = true, flowWidth = flowWidth, styleNames = styleNames
        )
        validateScope(template.blocks, scope, errors)
        val flows = template.blocks.filter { it is FlowBlock && it.table != null }
        if (flows.size > 1) {
            val second = template.blocks.indexOf(flows[1])
            errors += TemplateError("blocks[$second].table", "only one flow table per template (the engine lays out a single main table)")
        }
        return errors
    }

    fun require(template: Template, styleNames: Set<String>? = null) {
        val errors = validate(template, styleNames)
        if (errors.isNotEmpty()) throw TemplateException(errors)
    }

    // Width of the flow region as the template declares it: the sheet's content area (a flow with a table has no
    // size and `reserves` only cut its bottom). Compared at Length resolution (0.01 mm), no tolerance.
    private fun flowWidth(sheet: SheetSpec): Length {
        val format = SheetFormats.resolve(sheet)
        return format.width - sheet.margins.left.mm - sheet.margins.right.mm
    }

    private class Scope(
        val rootName: String,
        val rootHas: (String) -> Boolean,
        val prefix: String,
        val defaultAttach: AttachSpec,
        val params: Map<String, String?>?,
        val defs: Map<String, BlockSetDef>,
        val topLevel: Boolean,
        val flowWidth: Length? = null,
        val styleNames: Set<String>? = null
    )

    private fun validateSheet(sheet: SheetSpec, errors: MutableList<TemplateError>) {
        val format = sheet.format
        if (format != null) {
            if (sheet.width != null || sheet.height != null) {
                errors += TemplateError("sheet", "use either 'format' or 'width'/'height', not both")
            } else if (format.uppercase() !in SheetFormats.presets) {
                errors += TemplateError("sheet.format", "unknown format '$format' (${SheetFormats.presets.keys.joinToString()})")
            }
        } else if (sheet.width == null || sheet.height == null) {
            errors += TemplateError("sheet", "explicit size needs both 'width' and 'height'")
        } else if (sheet.width <= 0 || sheet.height <= 0) {
            errors += TemplateError("sheet", "width/height must be > 0")
        }
        for (name in sheet.anchors.keys) {
            if (!ID_PATTERN.matches(name) || name in SHEET_ANCHOR_NAMES) {
                errors += TemplateError("sheet.anchors.$name", "invalid or reserved anchor name '$name'")
            }
        }
    }

    private fun detectRecursion(defs: Map<String, BlockSetDef>, errors: MutableList<TemplateError>) {
        val state = HashMap<String, Int>()
        val stack = ArrayList<String>()
        fun visit(name: String) {
            state[name] = 1
            stack += name
            for (b in defs.getValue(name).blocks.filterIsInstance<BlockSetInstance>()) {
                if (b.use !in defs) continue
                when (state[b.use]) {
                    1 -> errors += TemplateError(
                        "blocksets.$name",
                        "recursive blockset use: ${(stack.subList(stack.indexOf(b.use), stack.size) + b.use).joinToString(" -> ")}"
                    )
                    null -> visit(b.use)
                }
            }
            stack.removeAt(stack.lastIndex)
            state[name] = 2
        }
        for (name in defs.keys) if (state[name] == null) visit(name)
    }

    private fun validateBlockSetDef(
        name: String,
        def: BlockSetDef,
        defs: Map<String, BlockSetDef>,
        errors: MutableList<TemplateError>
    ) {
        val path = "blocksets.$name"
        if (def.blocks.isEmpty()) errors += TemplateError("$path.blocks", "blockset must contain at least one block")
        val scope = Scope(
            rootName = "self", rootHas = { it == "topLeft" }, prefix = "$path.", defaultAttach = SELF_ATTACH,
            params = def.params, defs = defs, topLevel = false
        )
        validateScope(def.blocks, scope, errors)
        val byId = def.blocks.associateBy { it.id }
        for ((port, target) in def.ports) {
            val p = "$path.ports.$port"
            if (!ID_PATTERN.matches(port) || port in StdAnchor.keys) {
                errors += TemplateError(p, "invalid or reserved port name '$port'")
            }
            checkRef(target, p, scope, byId, errors)
        }
    }

    private fun validateScope(blocks: List<BlockSpec>, scope: Scope, errors: MutableList<TemplateError>) {
        val byId = LinkedHashMap<String, BlockSpec>()
        blocks.forEachIndexed { i, b ->
            val p = "${scope.prefix}blocks[$i]"
            when {
                !ID_PATTERN.matches(b.id) -> errors += TemplateError("$p.id", "invalid id '${b.id}' (letters, digits, _ and -)")
                b.id == "sheet" || b.id == "self" -> errors += TemplateError("$p.id", "id '${b.id}' is reserved")
                b.id in byId -> errors += TemplateError("$p.id", "duplicate id '${b.id}'")
            }
            byId.putIfAbsent(b.id, b)
        }

        blocks.forEachIndexed { i, b ->
            val p = "${scope.prefix}blocks[$i]"
            for (name in b.anchors.keys) {
                if (!ID_PATTERN.matches(name) || name in StdAnchor.keys) {
                    errors += TemplateError("$p.anchors.$name", "invalid or reserved anchor name '$name'")
                }
            }
            b.attach?.let { attach ->
                // single form: both axes carry the same self/to, so check (and report) once
                val axes = if (attach.perAxis) axisPaths(attach, "$p.attach") else axisPaths(attach, "$p.attach").take(1)
                for ((axisPath, axis) in axes) {
                    val self = normalizeAnchorName(axis.self)
                    if (!hasAnchor(b, self, scope.defs)) {
                        errors += TemplateError("$axisPath.self", "unknown anchor '${axis.self}' on block '${b.id}'")
                    }
                    val target = checkRef(axis.to, "$axisPath.to", scope, byId, errors)
                    if (target != null && !visibleCovers(target.visibleOn, b.visibleOn)) {
                        errors += TemplateError(
                            "$axisPath.to",
                            "block '${b.id}' (when: ${b.visibleOn.name.lowercase()}) attaches to '${target.id}' " +
                                "which exists only on ${target.visibleOn.name.lowercase()} pages"
                        )
                    }
                }
            }
            validateBlock(b, p, scope, errors)
        }

        val cycle = topoSort(blocks.distinctBy { it.id }, scope.defaultAttach)
        if (cycle is Topo.Cycle) {
            val first = blocks.indexOfFirst { it.id == cycle.ids.first() }
            errors += TemplateError(
                "${scope.prefix}blocks[$first].attach.to",
                "attach cycle: ${cycle.ids.joinToString(" -> ")}"
            )
        }
    }

    // Returns the target block, or null (root or error already reported).
    private fun checkRef(
        to: String,
        path: String,
        scope: Scope,
        byId: Map<String, BlockSpec>,
        errors: MutableList<TemplateError>
    ): BlockSpec? {
        val ref = parseAnchorRef(to)
        if (ref == null) {
            errors += TemplateError(path, "expected 'blockId.anchor', got '$to'")
            return null
        }
        val anchor = normalizeAnchorName(ref.anchor)
        if (ref.blockId == scope.rootName) {
            if (!scope.rootHas(anchor)) errors += TemplateError(path, "unknown anchor '$to'")
            return null
        }
        val target = byId[ref.blockId]
        if (target == null) {
            errors += TemplateError(path, "unknown block '${ref.blockId}' in '$to'")
            return null
        }
        if (target is FlowBlock && target.size == null) {
            errors += TemplateError(path, "flow block '${target.id}' has no size (it fills the flow region) and cannot be an attach target")
            return null
        }
        if (!hasAnchor(target, anchor, scope.defs)) {
            errors += TemplateError(path, "unknown anchor '$to'")
            return null
        }
        return target
    }

    // A dependant must be visible on no more pages than its target.
    private fun visibleCovers(target: PageSelector, dependant: PageSelector) =
        target == PageSelector.ALL || target == dependant

    private fun validateBlock(b: BlockSpec, p: String, scope: Scope, errors: MutableList<TemplateError>) {
        fun positive(n: Num, path: String) {
            if (n is Num.Lit && n.value <= 0.0) errors += TemplateError(path, "must be > 0, got ${n.value}")
        }
        fun size(s: SizeSpec) {
            positive(s.width, "$p.size.width")
            positive(s.height, "$p.size.height")
        }
        when (b) {
            is FrameBlock -> size(b.size)
            is RectBlock -> size(b.size)
            is TextBlock -> {
                size(b.size)
                if (b.rotate !in DRAWN_TEXT_ROTATIONS) errors += TemplateError("$p.rotate", "text rotation ${b.rotate} is not supported (expected 0|90)")
                if ((b.text == null) == (b.bind == null)) errors += TemplateError(p, "text block needs exactly one of 'text' / 'bind'")
                b.bind?.let { checkBind(it, "$p.bind", errors) }
                checkBindOptions(b.bind, b.format != null, b.optional, p, errors)
            }
            is FlowBlock -> {
                b.size?.let { size(it) }
                b.table?.let { validateFlowTable(b, it, "$p.table", scope, errors) }
                if (b.reserves) errors += TemplateError("$p.reserves", "flow block cannot reserve space")
                if (b.size == null && b.attach != null) errors += TemplateError("$p.attach", "flow block without size fills the flow region and cannot be attached")
                if (b.size == null && !scope.topLevel) errors += TemplateError("$p.size", "flow block inside a blockset needs a size")
            }
            is TableBlock -> validateTable(b, p, errors)
            is BlockSetInstance -> {
                val def = scope.defs[b.use]
                if (def == null) {
                    errors += TemplateError("$p.use", "unknown blockset '${b.use}'")
                } else {
                    for (arg in b.args.keys) {
                        if (arg !in def.params) errors += TemplateError("$p.args.$arg", "unknown param '$arg' of blockset '${b.use}'")
                    }
                    for ((param, default) in def.params) {
                        if (default == null && param !in b.args) {
                            errors += TemplateError("$p.args", "missing required param '$param' of blockset '${b.use}'")
                        }
                    }
                }
            }
        }
    }

    private fun checkBind(bind: String, path: String, errors: MutableList<TemplateError>) {
        if (!BIND_EXPR.matches(bind)) {
            errors += TemplateError(path, "bind must be a single path expression like \${doc.designation} (roots: ${DATA_ROOTS.joinToString()}), got '$bind'")
        }
    }

    private fun checkBindOptions(bind: String?, hasFormat: Boolean, optional: Boolean, path: String, errors: MutableList<TemplateError>) {
        if (bind != null) return
        if (hasFormat) errors += TemplateError("$path.format", "'format' needs 'bind'")
        if (optional) errors += TemplateError("$path.optional", "'optional' needs 'bind'")
    }

    // ("<base>" or "<base>.x" / "<base>.y") to axis, the YAML path of each axis.
    private fun axisPaths(attach: AttachSpec, base: String): List<Pair<String, AxisAttach>> =
        if (attach.perAxis) listOf("$base.x" to attach.x, "$base.y" to attach.y) else listOf(base to attach.x, base to attach.y)

    private fun validateTable(t: TableBlock, p: String, errors: MutableList<TemplateError>) {
        if (t.columns.isEmpty()) errors += TemplateError("$p.columns", "table needs at least one column")
        t.columns.forEachIndexed { i, c ->
            if (c is Num.Lit && c.value <= 0.0) errors += TemplateError("$p.columns[$i]", "must be > 0, got ${c.value}")
        }
        if (t.rows.isEmpty()) errors += TemplateError("$p.rows", "table needs at least one row")
        // declared row path for every expanded row (repeat.count rows share their declaration's path)
        val paths = ArrayList<String>()
        t.rows.forEachIndexed { i, r ->
            val rp = "$p.rows[$i]"
            when (r) {
                is FixedRow -> {
                    validateRow(r, rp, t.rotate, errors)
                    paths += rp
                }
                is RepeatRows -> {
                    if ((r.count == null) == (r.from == null)) {
                        errors += TemplateError("$rp.repeat", "repeat needs exactly one of 'count' / 'from'")
                    }
                    if (r.count != null && r.count < 0) errors += TemplateError("$rp.repeat.count", "must be >= 0")
                    validateRow(r.row, "$rp.repeat.row", t.rotate, errors)
                    repeat(r.count ?: 0) { paths += "$rp.repeat.row" }
                    // data-driven rows are not expanded; they must still fill the columns on their own
                    if (r.from != null) {
                        layoutGrid(listOf(r.row), t.columns.size).errors.forEach { errors += TemplateError("$rp.repeat.row.cells", it.message) }
                    }
                }
            }
        }
        layoutGrid(t.expandedRows(), t.columns.size).errors
            .map { TemplateError("${paths[it.rowIndex]}.cells", it.message) }
            .distinct()
            .forEach { errors += it }
    }

    private fun validateFlowTable(b: FlowBlock, t: FlowTableSpec, p: String, scope: Scope, errors: MutableList<TemplateError>) {
        if (b.size != null) errors += TemplateError("${p.removeSuffix(".table")}.size", "flow table fills the flow region, remove 'size'")
        if (b.visibleOn != PageSelector.ALL) errors += TemplateError("${p.removeSuffix(".table")}.when", "flow table is drawn on every page, 'when' must be all")
        if (!scope.topLevel) errors += TemplateError(p, "flow table is allowed only at the top level, not inside a blockset")
        if (t.rowHeight <= 0.0) errors += TemplateError("$p.rowHeight", "must be > 0, got ${t.rowHeight}")

        if (t.columns.isEmpty()) errors += TemplateError("$p.columns", "flow table needs at least one column")
        val ids = LinkedHashSet<String>()
        t.columns.forEachIndexed { i, c ->
            val cp = "$p.columns[$i]"
            when {
                !ID_PATTERN.matches(c.id) -> errors += TemplateError("$cp.id", "invalid id '${c.id}' (letters, digits, _ and -)")
                !ids.add(c.id) -> errors += TemplateError("$cp.id", "duplicate column id '${c.id}'")
            }
            if (c.width <= 0.0) errors += TemplateError("$cp.width", "must be > 0, got ${c.width}")
        }
        val flowWidth = scope.flowWidth
        if (flowWidth != null && t.columns.isNotEmpty() && t.columns.all { it.width > 0.0 }) {
            val total = t.columns.fold(Length.ZERO) { acc, c -> acc + c.width.mm }
            if (total != flowWidth) {
                errors += TemplateError(
                    "$p.columns",
                    "column widths sum to ${total.toMillimeters()} mm, flow region is ${flowWidth.toMillimeters()} mm wide " +
                        "(sheet content width, compared to 0.01 mm, no tolerance)"
                )
            }
        }

        val aliasKeys = t.styles.keys
        val known = scope.styleNames
        t.styles.forEach { (alias, style) ->
            val sp = "$p.styles.$alias"
            // object form: errors of `base` are at `.base`; the string form (no overrides) keeps the path of the alias
            val bp = if (style.asObject) "$sp.base" else sp
            if (!ID_PATTERN.matches(alias)) errors += TemplateError(sp, "invalid style alias '$alias' (letters, digits, _ and -)")
            if (known != null) {
                if (alias in known) errors += TemplateError(sp, "alias '$alias' shadows a built-in style")
                if (style.base !in known) errors += TemplateError(bp, "unknown style '${style.base}' (${known.sorted().joinToString()})")
            }
            if (style.size != null && !(style.size > 0.0 && style.size.isFinite())) errors += TemplateError("$sp.size", "must be > 0, got ${style.size}")
        }
        fun style(name: String?, path: String) {
            if (name == null || known == null || name in aliasKeys || name in known) return
            errors += TemplateError(path, "unknown style '$name' (aliases: ${aliasKeys.joinToString().ifEmpty { "none" }}; built-in: ${known.sorted().joinToString()})")
        }
        fun coverage(cells: Set<String>, path: String) {
            cells.filter { it !in ids }.forEach { errors += TemplateError("$path.$it", "unknown column '$it' (columns: ${ids.joinToString()})") }
            val missing = ids.filter { it !in cells }
            if (missing.isNotEmpty()) errors += TemplateError(path, "missing cell for column ${missing.joinToString { "'$it'" }}")
        }

        t.header?.let { h ->
            val hp = "$p.header"
            if (h.height <= 0.0) errors += TemplateError("$hp.height", "must be > 0, got ${h.height}")
            coverage(h.cells.keys, "$hp.cells")
            h.cells.forEach { (id, c) ->
                val cp = "$hp.cells.$id"
                if (c.rotate !in setOf(0, 90)) errors += TemplateError("$cp.rotate", "expected 0|90, got ${c.rotate}")
                if (c.lines != null && c.lines.isEmpty()) errors += TemplateError("$cp.lines", "must not be empty")
                if (c.lines != null && c.rotate != 0) errors += TemplateError("$cp.lines", "'lines' (manual break) is for horizontal text, not with rotate")
                style(c.style, "$cp.style")
            }
        }

        t.groupTitle?.let { g ->
            val gp = "$p.groupTitle"
            if (g.column !in ids) errors += TemplateError("$gp.column", "unknown column '${g.column}' (columns: ${ids.joinToString()})")
            if (g.spacerBefore < 0) errors += TemplateError("$gp.spacerBefore", "must be >= 0, got ${g.spacerBefore}")
            if (g.spacerAfter < 0) errors += TemplateError("$gp.spacerAfter", "must be >= 0, got ${g.spacerAfter}")
            style(g.style, "$gp.style")
        }

        coverage(t.rowCells.keys, "$p.row.cells")
        val lineColumns = mutableListOf<String>()
        t.rowCells.forEach { (id, c) ->
            val cp = "$p.row.cells.$id"
            rowContent(c.text, c.bind, c.format != null, c.optional, cp, errors, lineAllowed = true)
            if (c.bind == LINE_NUMBER_BIND) {
                if (c.cases.isNotEmpty()) errors += TemplateError("$cp.cases", "a cell bound to $LINE_NUMBER_BIND takes no 'cases' (the number is the whole content)")
                if (c.format != null) errors += TemplateError("$cp.format", "$LINE_NUMBER_BIND is an Integer, 'format' is not supported for it")
                lineColumns += id
            }
            c.cases.forEachIndexed { i, case ->
                val kp = "$cp.cases[$i]"
                predicateShape(case.where, "$kp.where", errors)
                rowContent(case.text, case.bind, case.format != null, case.optional, kp, errors)
            }
            style(c.style, "$cp.style")
        }

        lineColumns.drop(1).forEach {
            errors += TemplateError("$p.row.cells.$it.bind", "$LINE_NUMBER_BIND is already shown in column '${lineColumns.first()}', one column numbers the lines")
        }
        if (lineColumns.isEmpty() && t.lines != null) {
            errors += TemplateError("$p.lines", "'lines' needs a row cell bound to $LINE_NUMBER_BIND (no cell shows the line number)")
        }
        t.lines?.let { if (it.start < 0) errors += TemplateError("$p.lines.start", "must be >= 0, got ${it.start}") }

        // data shaping
        t.where?.let { predicateShape(it, "$p.where", errors) }
        t.groupBy?.let { g ->
            val gp = "$p.groupBy"
            if (g.order.isEmpty()) errors += TemplateError("$gp.order", "must list at least one value")
            g.order.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { errors += TemplateError("$gp.order", "duplicate value '$it'") }
            g.omit.filter { it in g.order }.forEach { errors += TemplateError("$gp.omit", "'$it' is also in 'order'") }
            g.order.filter { it !in g.titles }.forEach { errors += TemplateError("$gp.titles", "missing title for '$it'") }
            g.titles.keys.filter { it !in g.order }.forEach { errors += TemplateError("$gp.titles.$it", "'$it' is not in 'order' (a title belongs to a group)") }
        }
        if (t.groupTitle != null && t.groupBy == null) {
            errors += TemplateError("$p.groupTitle", "'groupTitle' needs 'groupBy' (there are no groups to title)")
        }
        t.sortBy.forEachIndexed { i, s -> if (s.field.isEmpty()) errors += TemplateError("$p.sortBy[$i].field", "must not be empty") }
        t.computed.forEach { (name, op) ->
            val cp = "$p.computed.$name"
            if (!ID_PATTERN.matches(name)) errors += TemplateError(cp, "invalid computed name '$name' (letters, digits, _ and -)")
            when (op) {
                is FlowComputed.Sequence -> if (op.step == 0L) errors += TemplateError("$cp.sequence.step", "must not be 0")
                is FlowComputed.Arithmetic -> {
                    val key = op.op.key
                    val exactly = op.op == ArithOp.SUBTRACT || op.op == ArithOp.DIVIDE
                    if (exactly && op.operands.size != 2) errors += TemplateError("$cp.$key", "$key takes exactly 2 operands, got ${op.operands.size}")
                    if (!exactly && op.operands.size < 2) errors += TemplateError("$cp.$key", "$key takes at least 2 operands, got ${op.operands.size}")
                    if (op.scale != null && op.scale < 0) errors += TemplateError("$cp.scale", "must be >= 0, got ${op.scale}")
                }
            }
        }

        val totalIds = HashSet<String>()
        t.totals.forEachIndexed { i, total ->
            val tp = "$p.totals[$i]"
            when {
                !ID_PATTERN.matches(total.id) -> errors += TemplateError("$tp.id", "invalid id '${total.id}' (letters, digits, _ and -)")
                !totalIds.add(total.id) -> errors += TemplateError("$tp.id", "duplicate total id '${total.id}'")
            }
            if (total.scope == TotalScope.GROUP && t.groupBy == null) {
                errors += TemplateError("$tp.scope", "scope 'group' needs 'groupBy' (there are no groups to total)")
            }
            if (total.agg == TotalAgg.COUNT) {
                if (total.field != null) errors += TemplateError("$tp.field", "count counts rows and takes no 'field'")
            } else if (total.field == null) {
                errors += TemplateError(tp, "${total.agg.key} needs a 'field' (an Integer or Decimal item or computed field)")
            }
            if (total.agg == TotalAgg.AVG) {
                if (total.scale == null) errors += TemplateError(tp, "avg needs 'scale' (the mean is rounded explicitly)")
                if (total.rounding == null) errors += TemplateError(tp, "avg needs 'rounding' (the mean is rounded explicitly)")
                if (total.scale != null && total.scale < 0) errors += TemplateError("$tp.scale", "must be >= 0, got ${total.scale}")
            } else {
                if (total.scale != null) errors += TemplateError("$tp.scale", "'scale' applies to avg only")
                if (total.rounding != null) errors += TemplateError("$tp.rounding", "'rounding' applies to avg only")
            }
            if (total.label.isEmpty()) errors += TemplateError("$tp.label", "must not be empty")
            for ((key, column) in listOf("labelColumn" to total.labelColumn, "valueColumn" to total.valueColumn)) {
                if (column !in ids) errors += TemplateError("$tp.$key", "unknown column '$column' (columns: ${ids.joinToString()})")
            }
            if (total.labelColumn == total.valueColumn) errors += TemplateError("$tp.valueColumn", "label and value need different columns, both are '${total.valueColumn}'")
            style(total.style, "$tp.style")
            total.where?.let { predicateShape(it, "$tp.where", errors) }
        }
    }

    // Content of a cell or a case: `text` xor `bind` (an `item` path), `format` / `optional` need a bind.
    // `lineAllowed`: the bind of a cell itself (not of a case) may be ${line.number}, the layout-derived line counter.
    private fun rowContent(
        text: String?, bind: String?, hasFormat: Boolean, optional: Boolean, path: String, errors: MutableList<TemplateError>,
        lineAllowed: Boolean = false
    ) {
        if (text != null && bind != null) errors += TemplateError(path, "cell has both 'text' and 'bind'")
        bind?.let {
            checkBind(it, "$path.bind", errors)
            if (BIND_EXPR.matches(it) && it == LINE_NUMBER_BIND) {
                if (!lineAllowed) errors += TemplateError("$path.bind", "$LINE_NUMBER_BIND is the bind of a row cell, it cannot be a 'cases' variant")
            } else if (BIND_EXPR.matches(it) && !Binding.path(it).startsWith("item.")) {
                errors += TemplateError("$path.bind", "flow row binds read the row record: expected \${item.<field>} (or $LINE_NUMBER_BIND), got '$it'")
            }
        }
        checkBindOptions(bind, hasFormat, optional, path, errors)
    }

    // Shape rules the loader cannot see: no empty and / or, no empty `in`.
    private fun predicateShape(pr: Predicate, path: String, errors: MutableList<TemplateError>) {
        when (pr) {
            is Predicate.And -> {
                if (pr.items.isEmpty()) errors += TemplateError("$path.and", "must not be empty")
                pr.items.forEachIndexed { i, q -> predicateShape(q, "$path.and[$i]", errors) }
            }
            is Predicate.Or -> {
                if (pr.items.isEmpty()) errors += TemplateError("$path.or", "must not be empty")
                pr.items.forEachIndexed { i, q -> predicateShape(q, "$path.or[$i]", errors) }
            }
            is Predicate.Not -> predicateShape(pr.item, "$path.not", errors)
            is Predicate.In -> if (pr.values.isEmpty()) errors += TemplateError("$path.in", "must not be empty")
            else -> {}
        }
    }

    private fun validateRow(row: FixedRow, p: String, tableRotate: Int, errors: MutableList<TemplateError>) {
        if (row.height is Num.Lit && row.height.value <= 0.0) errors += TemplateError("$p.height", "must be > 0, got ${row.height.value}")
        row.cells.forEachIndexed { i, c ->
            val cp = "$p.cells[$i]"
            if (c.text != null && c.bind != null) errors += TemplateError(cp, "cell has both 'text' and 'bind'")
            // the resolver draws a cell rotated by the table's rotation plus its own; the Layout IR has 0 and 90 only
            val effective = (tableRotate + c.rotate) % 360
            if (effective !in DRAWN_TEXT_ROTATIONS) {
                errors += TemplateError("$cp.rotate", "text rotation $effective (table rotate $tableRotate + cell rotate ${c.rotate}) is not supported (expected 0|90)")
            }
            if (c.span < 1) errors += TemplateError("$cp.span", "must be >= 1")
            if (c.rowSpan < 1) errors += TemplateError("$cp.rowSpan", "must be >= 1")
            c.bind?.let { checkBind(it, "$cp.bind", errors) }
            checkBindOptions(c.bind, c.format != null, c.optional, cp, errors)
        }
    }
}

// Text rotations the Layout IR can draw (horizontal, bottom to top). Anything else is rejected at validation.
private val DRAWN_TEXT_ROTATIONS = setOf(0, 90)

// Structural anchor existence (independent of numeric values / params).
internal fun hasAnchor(block: BlockSpec, rawName: String, defs: Map<String, BlockSetDef>): Boolean {
    val name = normalizeAnchorName(rawName)
    if (name in StdAnchor.keys || name in block.anchors) return true
    return when (block) {
        is TableBlock -> tableHasAnchor(block, name)
        is BlockSetInstance -> defs[block.use]?.ports?.containsKey(name) == true
        else -> false
    }
}

private fun tableHasAnchor(t: TableBlock, name: String): Boolean {
    COL_ANCHOR.matchEntire(name)?.let { return it.groupValues[1].toInt() < t.columns.size }
    val rows = t.expandedRows()
    ROW_ANCHOR.matchEntire(name)?.let { return it.groupValues[1].toInt() < rows.size }
    CELL_ANCHOR.matchEntire(name)?.let {
        val r = it.groupValues[1].toInt()
        val c = it.groupValues[2].toInt()
        return it.groupValues[3] in StdAnchor.keys && r < rows.size && t.grid().cells.any { cell -> cell.row == r && cell.col == c }
    }
    return false
}

// Static rows only; `repeat.from` rows are data-driven and contribute nothing until the engine fills them.
internal fun TableBlock.expandedRows(): List<FixedRow> = rows.flatMap { r ->
    when (r) {
        is FixedRow -> listOf(r)
        is RepeatRows -> List(r.count ?: 0) { r.row }
    }
}

internal val SHEET_ANCHOR_NAMES: Set<String> = StdAnchor.keys +
    setOf("contentTopLeft", "contentTopRight", "contentBottomLeft", "contentBottomRight")
