package dev.reportgenerator.template

// Contract between a template and the data it is bound to: every `bind` must name a scalar field of the
// schema, and its `format` must fit the field's type. Runs before layout and collects all problems with
// YAML paths. Binds inside blockset definitions are checked once, at `blocksets.<name>.blocks[...]`
// (a bind cannot contain `${param.x}`, so every instance has the same binds). Whether a value is actually
// present is only known at layout time (see Binding.render, `optional`).
object TemplateContract {
    fun check(template: Template, schema: DataSchema): List<TemplateError> {
        val errors = ArrayList<TemplateError>()
        for ((name, def) in template.blocksets) checkBlocks(def.blocks, "blocksets.$name.", schema, errors)
        checkBlocks(template.blocks, "", schema, errors)
        return errors
    }

    // One flow table on its own (the engine builds it from rows supplied by code); `path` = its YAML location.
    fun checkFlowTable(spec: FlowTableSpec, schema: DataSchema, path: String): List<TemplateError> =
        ArrayList<TemplateError>().also { FlowContract.check(spec, schema, path, it) }

    fun requireFlowTable(spec: FlowTableSpec, schema: DataSchema, path: String) {
        val errors = checkFlowTable(spec, schema, path)
        if (errors.isNotEmpty()) throw TemplateException(errors)
    }

    fun require(template: Template, schema: DataSchema) {
        val errors = check(template, schema)
        if (errors.isNotEmpty()) throw TemplateException(errors)
    }

    private fun checkBlocks(blocks: List<BlockSpec>, prefix: String, schema: DataSchema, errors: MutableList<TemplateError>) {
        blocks.forEachIndexed { i, b ->
            val p = "${prefix}blocks[$i]"
            when (b) {
                is TextBlock -> b.bind?.let { checkBind(it, b.format, p, schema, errors) }
                is TableBlock -> b.rows.forEachIndexed { r, row ->
                    when (row) {
                        is FixedRow -> checkRow(row, "$p.rows[$r]", schema, errors)
                        is RepeatRows -> checkRow(row.row, "$p.rows[$r].repeat.row", schema, errors)
                    }
                }
                // a flow table reads the row record (root `item`) of the schema (FlowContract); the rest is static
                is FlowBlock -> b.table?.let { FlowContract.check(it, schema, "$p.table", errors) }
                else -> {}
            }
        }
    }

    private fun checkRow(row: FixedRow, path: String, schema: DataSchema, errors: MutableList<TemplateError>) {
        row.cells.forEachIndexed { i, c -> c.bind?.let { checkBind(it, c.format, "$path.cells[$i]", schema, errors) } }
    }

    // `owner` = YAML path of the cell / text block that holds the bind. `allowLine`: the cell is a flow table row cell
    // (not a `cases` variant), the only place `line.*` (the layout-derived number of a physical line) exists.
    internal fun checkBind(
        bind: String, format: FormatSpec?, owner: String, schema: DataSchema, errors: MutableList<TemplateError>,
        allowLine: Boolean = false
    ) {
        val path = Binding.path(bind)
        if (!BIND_EXPR.matches(bind)) {
            errors += TemplateError("$owner.bind", "bind must be a single path expression like \${doc.designation}, got '$bind'")
            return
        }
        if (path.startsWith("line.") && !allowLine) {
            errors += TemplateError("$owner.bind", "'$path' exists only in the row cells of a flow table (the number of a physical line), not here")
            return
        }
        val type = when (val found = schema.lookup(path)) {
            is DataSchema.Lookup.Found -> found.type
            is DataSchema.Lookup.UnknownRoot ->
                return run { errors += TemplateError("$owner.bind", "unknown root '${found.root}' (${DATA_ROOTS.joinToString()})") }
            is DataSchema.Lookup.UnknownField -> return run {
                val known = if (found.known.isEmpty()) "no fields" else ": ${found.known.joinToString()}"
                val hint = nearest(found.field, found.known)?.let { ", did you mean '${found.parent}.$it'?" }.orEmpty()
                errors += TemplateError("$owner.bind", "unknown field '$path' (${found.parent} has${if (found.known.isEmpty()) " " else ""}$known)$hint")
            }
            is DataSchema.Lookup.NotARecord -> return run {
                errors += TemplateError("$owner.bind", "cannot read '$path': '${found.parent}' is a ${found.type.typeName}, not a record")
            }
        }
        if (!type.isScalar) {
            errors += TemplateError("$owner.bind", "'$path' is a ${type.typeName}, not a scalar value")
            return
        }
        if (format != null) ValueFormatter.problem(type, format)?.let { errors += TemplateError("$owner.format", it) }
    }

    // Closest known name within edit distance 2 (case-insensitive), or null.
    internal fun nearest(name: String, known: List<String>): String? =
        known.map { it to distance(name.lowercase(), it.lowercase()) }.filter { it.second <= 2 }.minByOrNull { it.second }?.first

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
