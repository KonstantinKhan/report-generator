package dev.reportgenerator.template

import java.math.BigDecimal
import java.math.BigInteger
import java.text.Collator
import java.time.LocalDate
import java.util.Locale

// Data side of a flow table: what the YAML `where` / `sortBy` / `groupBy` / `computed` / `cases` do with the
// rows (records of the `item` root). A closed set of structured operations, no expressions. The contract
// (FlowContract) checks the spec against the schema before this runs, so the failures here (IllegalStateException)
// only mean data that does not fit its own schema. The layout algorithm stays in report-layout.

// The `item` record type of the spec: the adapter's fields plus the `computed` names (a sequence is Integer,
// arithmetic is inferred, see computedTypes; a computed field that does not resolve is left out, the contract
// reports it).
fun FlowTableSpec.itemFields(schema: DataSchema): Map<String, DataType> {
    val raw = schema.roots.getValue("item").fields
    return raw + computedTypes(raw).types
}

// The schema row binds and `cases` are checked against: `item` extended with the computed fields.
fun FlowTableSpec.itemSchema(schema: DataSchema): DataSchema =
    if (computed.isEmpty()) schema else DataSchema(schema.roots + ("item" to DataType.Record(itemFields(schema))))

// Enum values the template itself names per `item` field (groupBy order / omit / titles, eq / ne / in of every
// predicate). A data source that has no declared schema (a data file) uses them as the domain of the field.
fun FlowTableSpec.declaredEnumValues(): Map<String, Set<String>> {
    val found = LinkedHashMap<String, MutableSet<String>>()
    fun add(field: String, values: Collection<String>) { found.getOrPut(field) { linkedSetOf() } += values }
    fun walk(p: Predicate?) {
        when (p) {
            null -> {}
            is Predicate.Eq -> add(p.field, listOf(p.value))
            is Predicate.Ne -> add(p.field, listOf(p.value))
            is Predicate.In -> add(p.field, p.values)
            is Predicate.And -> p.items.forEach(::walk)
            is Predicate.Or -> p.items.forEach(::walk)
            is Predicate.Not -> walk(p.item)
            is Predicate.IsNull, is Predicate.NotNull -> {}
        }
    }
    groupBy?.let { add(it.field, it.order + it.omit + it.titles.keys) }
    walk(where)
    rowCells.values.forEach { c -> c.cases.forEach { walk(it.where) } }
    totals.forEach { walk(it.where) }
    return found
}

// Value of a YAML literal as the field's type, null when it does not fit (Enum: not a declared member).
internal fun literal(type: DataType, text: String): DataValue? = when (type) {
    DataType.Str -> DataValue.Str(text)
    DataType.Integer -> text.toLongOrNull()?.let { DataValue.Integer(it) }
    DataType.Decimal -> text.toBigDecimalOrNull()?.let { DataValue.Decimal(it) }
    DataType.Date -> runCatching { LocalDate.parse(text) }.getOrNull()?.let { DataValue.Date(it) }
    DataType.Bool -> when (text.lowercase()) { "true" -> DataValue.Bool(true); "false" -> DataValue.Bool(false); else -> null }
    is DataType.Enum -> text.takeIf { it in type.values }?.let { DataValue.Enum(it) }
    is DataType.ListOf, is DataType.Record -> null
}

// Typed equality (Decimal by numeric value, 1.0 == 1.00).
internal fun sameValue(a: DataValue, b: DataValue): Boolean =
    if (a is DataValue.Decimal && b is DataValue.Decimal) a.value.compareTo(b.value) == 0 else a == b

// Order of two present values of one type: numbers numerically, Date chronologically, false < true, Enum by the
// declared order of its values, String naturally (see NaturalOrder).
internal fun compareValues(type: DataType, a: DataValue, b: DataValue): Int = when {
    a is DataValue.Str && b is DataValue.Str -> NaturalOrder.compare(a.value, b.value)
    a is DataValue.Integer && b is DataValue.Integer -> a.value.compareTo(b.value)
    a is DataValue.Decimal && b is DataValue.Decimal -> a.value.compareTo(b.value)
    a is DataValue.Date && b is DataValue.Date -> a.value.compareTo(b.value)
    a is DataValue.Bool && b is DataValue.Bool -> a.value.compareTo(b.value)
    a is DataValue.Enum && b is DataValue.Enum ->
        ((type as? DataType.Enum)?.values ?: emptyList()).let { it.indexOf(a.name).compareTo(it.indexOf(b.name)) }
    else -> error("cannot compare ${a.type.typeName} with ${b.type.typeName}")
}

// Natural string order for `sortBy`: digit runs by their number ("Вал 2" before "Вал 10"), the rest by the Russian
// collation at primary strength (letters alphabetically, case ignored). Equal keys compare 0 (the sort is stable).
object NaturalOrder : Comparator<String> {
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.PRIMARY }
    private val CHUNK = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a).map { it.value }.toList()
        val y = CHUNK.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val bothNumbers = x[i][0].isDigit() && y[i][0].isDigit()
            val c = if (bothNumbers) BigInteger(x[i]).compareTo(BigInteger(y[i])) else collator.compare(x[i], y[i])
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

// `where` / case predicate compiled against the field types (literals parsed once). `fields` = what the
// predicate may read (a record's fields, or those plus the computed names).
internal fun compilePredicate(p: Predicate, fields: Map<String, DataType>): (DataValue.Record) -> Boolean {
    fun typeOf(field: String): DataType = fields[field] ?: error("unknown field '$field'")
    fun lit(field: String, text: String): DataValue =
        literal(typeOf(field), text) ?: error("'$text' is not a ${typeOf(field).typeName} (field '$field')")
    return when (p) {
        is Predicate.Eq -> lit(p.field, p.value).let { v -> { r -> r.fields[p.field]?.let { sameValue(it, v) } ?: false } }
        is Predicate.Ne -> lit(p.field, p.value).let { v -> { r -> r.fields[p.field]?.let { !sameValue(it, v) } ?: true } }
        is Predicate.In -> p.values.map { lit(p.field, it) }.let { vs -> { r -> r.fields[p.field]?.let { f -> vs.any { sameValue(f, it) } } ?: false } }
        is Predicate.IsNull -> { r -> r.fields[p.field] == null }
        is Predicate.NotNull -> { r -> r.fields[p.field] != null }
        is Predicate.And -> p.items.map { compilePredicate(it, fields) }.let { ps -> { r -> ps.all { it(r) } } }
        is Predicate.Or -> p.items.map { compilePredicate(it, fields) }.let { ps -> { r -> ps.any { it(r) } } }
        is Predicate.Not -> compilePredicate(p.item, fields).let { q -> { r -> !q(r) } }
    }
}

// One group of the shaped table; `title` is null for a flat table (no `groupBy`). `totals` = the group's `totals`
// (scope group) in spec order, empty without any.
class ShapedGroup(val title: String?, val rows: List<DataValue.Record>, val totals: List<TotalValue> = emptyList())

// The whole shaped table: groups plus the table-scope totals (over the rows of all groups, as drawn).
class ShapedTable(val groups: List<ShapedGroup>, val totals: List<TotalValue>)

object FlowShaper {
    // `rows` are records of the `item` root in source order. where -> arithmetic computed -> sortBy (stable) ->
    // groupBy -> sequences -> totals. Returned rows carry the computed fields. A flat table is a single group
    // without a title.
    fun shape(spec: FlowTableSpec, schema: DataSchema, rows: List<DataValue.Record>): List<ShapedGroup> =
        shapeTable(spec, schema, rows).groups

    fun shapeTable(spec: FlowTableSpec, schema: DataSchema, rows: List<DataValue.Record>): ShapedTable {
        val raw = schema.roots.getValue("item").fields
        val computedTypes = spec.computedTypes(raw)

        val keep = spec.where?.let { compilePredicate(it, raw) }
        val kept = rows.withIndex().filter { keep == null || keep(it.value) }
        val calculated = arithmetic(spec, computedTypes, kept)
        val sortFields = raw + computedTypes.types.filterKeys { spec.computed[it] is FlowComputed.Arithmetic }
        val sorted = if (spec.sortBy.isEmpty()) calculated else calculated.sortedWith(comparator(spec.sortBy, sortFields))

        val groups: List<Pair<String?, List<DataValue.Record>>> = spec.groupBy?.let { g ->
            val type = raw[g.field] ?: error("groupBy field '${g.field}' is not an item field")
            val byValue = sorted.groupBy { r ->
                (r.fields[g.field] as? DataValue.Enum)?.name ?: error("row without a value for groupBy field '${g.field}': $r")
            }
            val unknown = byValue.keys - g.order.toSet() - g.omit.toSet()
            check(unknown.isEmpty()) { "groupBy.field '${g.field}' (${type.typeName}) has values ${unknown.joinToString()} not listed in order or omit" }
            g.order.mapNotNull { v ->
                val members = byValue[v].orEmpty()
                if (members.isEmpty() && g.skipEmpty) null else (g.titles[v] ?: error("no title for group '$v'")) to members
            }
        } ?: listOf(null to sorted)

        val numbered = numbered(spec, groups)
        if (spec.totals.isEmpty()) return ShapedTable(numbered, emptyList())
        val totals = FlowTotals(spec.totals, raw + computedTypes.types)
        return ShapedTable(
            numbered.map { ShapedGroup(it.title, it.rows, totals.group(it.rows)) },
            totals.table(numbered.flatMap { it.rows })
        )
    }

    // Arithmetic computed fields on the kept rows (source order), in dependency order. Failures name the source row.
    private fun arithmetic(spec: FlowTableSpec, types: ComputedTypes, rows: List<IndexedValue<DataValue.Record>>): List<DataValue.Record> {
        if (types.order.isEmpty()) return rows.map { it.value }
        return rows.map { (index, row) ->
            val known = LinkedHashMap<String, DataValue>()
            for (name in types.order) {
                FlowArithmetic.evaluate(
                    name, spec.computed.getValue(name) as FlowComputed.Arithmetic, types.types.getValue(name), row, known
                ) { FlowArithmetic.describe(row, index) }?.let { known[name] = it }
            }
            DataValue.Record(row.fields + known)
        }
    }

    private fun comparator(keys: List<FlowSort>, fields: Map<String, DataType>): Comparator<DataValue.Record> =
        Comparator { a, b ->
            for (k in keys) {
                val type = fields[k.field] ?: error("sortBy field '${k.field}' is not an item field")
                val x = a.fields[k.field]
                val y = b.fields[k.field]
                val c = when {
                    x == null && y == null -> 0
                    x == null -> if (k.nulls == NullsOrder.FIRST) -1 else 1
                    y == null -> if (k.nulls == NullsOrder.FIRST) 1 else -1
                    else -> compareValues(type, x, y).let { if (k.order == SortOrder.DESC) -it else it }
                }
                if (c != 0) return@Comparator c
            }
            0
        }

    // Adds the sequence fields; `scope: table` counts through all groups in table order.
    private fun numbered(spec: FlowTableSpec, groups: List<Pair<String?, List<DataValue.Record>>>): List<ShapedGroup> {
        val sequences = spec.computed.mapNotNull { (name, op) -> (op as? FlowComputed.Sequence)?.let { name to it } }
        if (sequences.isEmpty()) return groups.map { (t, r) -> ShapedGroup(t, r) }
        val tableCounters = HashMap<String, Long>()
        return groups.map { (title, rows) ->
            val groupCounters = HashMap<String, Long>()
            ShapedGroup(title, rows.map { row ->
                val extra = sequences.associate { (name, op) ->
                    val counters = if (op.scope == SequenceScope.TABLE) tableCounters else groupCounters
                    val index = counters.merge(name, 1L, Long::plus)!! - 1
                    name to DataValue.Integer(op.start + op.step * index)
                }
                DataValue.Record(row.fields + extra)
            })
        }
    }
}

// Text of a row cell: the first matching `cases` entry, else the cell's own content, else empty.
// `fields` = the extended item fields (record + computed), `data` the context the bind is read from.
class FlowCellRenderer(private val cell: FlowRowCell, fields: Map<String, DataType>) {
    private val cases = cell.cases.map { compilePredicate(it.where, fields) to it }

    // `source` = the item rows of the data file in source order: a failure (no value for a bind) then names the
    // row ("source row N {fields}: ...", like the arithmetic errors); `item` is matched by its source fields.
    fun render(item: DataValue.Record, data: DataContext, source: List<DataValue.Record> = emptyList()): String = try {
        render(item, data)
    } catch (e: IllegalStateException) {
        val index = source.indexOfFirst { row -> row.fields.all { (k, v) -> item.fields[k] == v } }
        if (index < 0) throw e
        throw IllegalStateException("${FlowArithmetic.describe(source[index], index)}: ${e.message}", e)
    }

    fun render(item: DataValue.Record, data: DataContext): String {
        val case = cases.firstOrNull { (matches, _) -> matches(item) }?.second
        val text = if (case != null) case.text else cell.text
        val bind = if (case != null) case.bind else cell.bind
        val format = if (case != null) case.format else cell.format
        val optional = if (case != null) case.optional else cell.optional
        return bind?.let { Binding.render(it, format, optional, data) } ?: text.orEmpty()
    }
}
