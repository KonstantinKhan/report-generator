package dev.reportgenerator.template

// Semantic checks on a parsed Template: ids, attach references (block + anchor existence), cycles,
// `when` compatibility, table shape, blockset params/ports and recursion. Collects all errors.
object TemplateValidator {
    fun validate(template: Template): List<TemplateError> {
        val errors = ArrayList<TemplateError>()
        val sheet = template.sheet
        validateSheet(sheet, errors)

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
            params = null, defs = template.blocksets, topLevel = true
        )
        validateScope(template.blocks, scope, errors)
        return errors
    }

    fun require(template: Template) {
        val errors = validate(template)
        if (errors.isNotEmpty()) throw TemplateException(errors)
    }

    private class Scope(
        val rootName: String,
        val rootHas: (String) -> Boolean,
        val prefix: String,
        val defaultAttach: AttachSpec,
        val params: Map<String, String?>?,
        val defs: Map<String, BlockSetDef>,
        val topLevel: Boolean
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
                if ((b.text == null) == (b.bind == null)) errors += TemplateError(p, "text block needs exactly one of 'text' / 'bind'")
                b.bind?.let { checkBind(it, "$p.bind", errors) }
                checkBindOptions(b.bind, b.format != null, b.optional, p, errors)
            }
            is FlowBlock -> {
                b.size?.let { size(it) }
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
            errors += TemplateError(path, "bind must be a single path expression like \${doc.designation} (roots: doc, page, item), got '$bind'")
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
                    validateRow(r, rp, errors)
                    paths += rp
                }
                is RepeatRows -> {
                    if ((r.count == null) == (r.from == null)) {
                        errors += TemplateError("$rp.repeat", "repeat needs exactly one of 'count' / 'from'")
                    }
                    if (r.count != null && r.count < 0) errors += TemplateError("$rp.repeat.count", "must be >= 0")
                    validateRow(r.row, "$rp.repeat.row", errors)
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

    private fun validateRow(row: FixedRow, p: String, errors: MutableList<TemplateError>) {
        if (row.height is Num.Lit && row.height.value <= 0.0) errors += TemplateError("$p.height", "must be > 0, got ${row.height.value}")
        row.cells.forEachIndexed { i, c ->
            val cp = "$p.cells[$i]"
            if (c.text != null && c.bind != null) errors += TemplateError(cp, "cell has both 'text' and 'bind'")
            if (c.span < 1) errors += TemplateError("$cp.span", "must be >= 1")
            if (c.rowSpan < 1) errors += TemplateError("$cp.rowSpan", "must be >= 1")
            c.bind?.let { checkBind(it, "$cp.bind", errors) }
            checkBindOptions(c.bind, c.format != null, c.optional, cp, errors)
        }
    }
}

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
