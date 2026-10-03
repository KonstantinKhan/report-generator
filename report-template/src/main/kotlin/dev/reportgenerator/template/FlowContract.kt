package dev.reportgenerator.template

// Contract of a flow table with the schema of the `item` record: every field named by `where`, `sortBy`,
// `groupBy`, `cases` exists and is a scalar, literals fit the field's type (Enum: a member of the schema's
// values), `groupBy` reads an Enum field and covers every value of it (in `order` or `omit`, nothing is dropped
// silently), `computed` names do not clash with record fields, binds and their `format` fit the types (computed
// fields are Integer). Errors carry YAML paths. Called by TemplateContract for the `flow` block.
internal object FlowContract {
    fun check(t: FlowTableSpec, schema: DataSchema, p: String, errors: MutableList<TemplateError>) {
        val raw = schema.roots.getValue("item").fields
        val extended = t.itemFields(schema)
        val computedNames = t.computed.keys

        computedNames.filter { it in raw }.forEach {
            errors += TemplateError("$p.computed.$it", "computed field '$it' clashes with a field of item (${raw.keys.joinToString()})")
        }
        t.where?.let { predicate(it, "$p.where", raw, computedNames, errors) }
        t.sortBy.forEachIndexed { i, s ->
            field(s.field, "$p.sortBy[$i].field", raw, computedNames, errors)
        }
        t.groupBy?.let { groupBy(it, "$p.groupBy", raw, computedNames, errors) }

        val itemSchema = t.itemSchema(schema)
        t.rowCells.forEach { (id, c) ->
            val cp = "$p.row.cells.$id"
            c.bind?.let { TemplateContract.checkBind(it, c.format, cp, itemSchema, errors) }
            c.cases.forEachIndexed { i, case ->
                val kp = "$cp.cases[$i]"
                predicate(case.where, "$kp.where", extended, emptySet(), errors)
                case.bind?.let { TemplateContract.checkBind(it, case.format, kp, itemSchema, errors) }
            }
        }
    }

    // Type of a scalar item field, or null after reporting why it cannot be used. `computed` names are unknown here
    // (they exist only after shaping).
    private fun field(name: String, path: String, fields: Map<String, DataType>, computed: Set<String>, errors: MutableList<TemplateError>): DataType? {
        val type = fields[name]
        if (type == null) {
            val message = if (name in computed) "computed field '$name' is not available here (where / sortBy / groupBy read the record's own fields)"
            else {
                val hint = TemplateContract.nearest(name, fields.keys.toList())?.let { ", did you mean '$it'?" }.orEmpty()
                "unknown item field '$name' (${fields.keys.ifEmpty { setOf("none") }.joinToString()})$hint"
            }
            errors += TemplateError(path, message)
            return null
        }
        if (!type.isScalar) {
            errors += TemplateError(path, "item field '$name' is a ${type.typeName}, not a scalar value")
            return null
        }
        return type
    }

    private fun predicate(pr: Predicate, path: String, fields: Map<String, DataType>, computed: Set<String>, errors: MutableList<TemplateError>) {
        fun lit(name: String, type: DataType, text: String, at: String) {
            if (literal(type, text) != null) return
            val why = if (type is DataType.Enum) "is not one of ${type.values.joinToString("|")}" else "is not a valid ${type.typeName}"
            errors += TemplateError(at, "'$text' $why (field '$name')")
        }
        when (pr) {
            is Predicate.Eq -> field(pr.field, "$path.field", fields, computed, errors)?.let { lit(pr.field, it, pr.value, "$path.eq") }
            is Predicate.Ne -> field(pr.field, "$path.field", fields, computed, errors)?.let { lit(pr.field, it, pr.value, "$path.ne") }
            is Predicate.In -> field(pr.field, "$path.field", fields, computed, errors)?.let { type ->
                pr.values.forEachIndexed { i, v -> lit(pr.field, type, v, "$path.in[$i]") }
            }
            is Predicate.IsNull -> field(pr.field, "$path.field", fields, computed, errors)
            is Predicate.NotNull -> field(pr.field, "$path.field", fields, computed, errors)
            is Predicate.And -> pr.items.forEachIndexed { i, q -> predicate(q, "$path.and[$i]", fields, computed, errors) }
            is Predicate.Or -> pr.items.forEachIndexed { i, q -> predicate(q, "$path.or[$i]", fields, computed, errors) }
            is Predicate.Not -> predicate(pr.item, "$path.not", fields, computed, errors)
        }
    }

    private fun groupBy(g: FlowGroupBy, p: String, fields: Map<String, DataType>, computed: Set<String>, errors: MutableList<TemplateError>) {
        val type = field(g.field, "$p.field", fields, computed, errors) ?: return
        if (type !is DataType.Enum) {
            errors += TemplateError("$p.field", "groupBy needs an Enum field, '${g.field}' is ${type.typeName}")
            return
        }
        fun member(value: String, at: String) {
            if (value !in type.values) errors += TemplateError(at, "'$value' is not a value of '${g.field}' (${type.values.joinToString("|")})")
        }
        g.order.forEachIndexed { i, v -> member(v, "$p.order[$i]") }
        g.omit.forEachIndexed { i, v -> member(v, "$p.omit[$i]") }
        g.titles.keys.forEach { member(it, "$p.titles.$it") }
        val uncovered = type.values.filter { it !in g.order && it !in g.omit }
        if (uncovered.isNotEmpty()) {
            errors += TemplateError(
                "$p.order",
                "'${g.field}' can also be ${uncovered.joinToString("|")}: list ${if (uncovered.size == 1) "it" else "them"} in 'order' " +
                    "(a group) or 'omit' (dropped on purpose), rows must not be lost silently"
            )
        }
    }
}
