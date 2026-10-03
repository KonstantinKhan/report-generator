package dev.reportgenerator.template

import java.math.BigDecimal
import java.time.LocalDate

// What a template is bound to at layout time: the declared schema (used by the contract check before
// layout) and the values. `path` is a bind path without `${}`, e.g. "doc.designation". null = no value.
interface DataContext {
    val schema: DataSchema

    fun get(path: String): DataValue?

    // Values known only at layout time (page.number, page.total) laid over this context.
    fun overlay(values: Map<String, DataValue>): DataContext = OverlayDataContext(this, values)

    companion object {
        // No document data, only the always-declared page.* fields.
        val EMPTY: DataContext = MapDataContext(DataSchema(), emptyMap())
    }
}

// Values as nested records per root; `get("doc.customer.name")` walks them.
class MapDataContext(
    override val schema: DataSchema,
    private val roots: Map<String, DataValue.Record>
) : DataContext {
    override fun get(path: String): DataValue? {
        val parts = path.split('.')
        var current: DataValue = roots[parts[0]] ?: return null
        for (i in 1 until parts.size) {
            current = (current as? DataValue.Record)?.fields?.get(parts[i]) ?: return null
        }
        return current
    }
}

class OverlayDataContext(private val base: DataContext, private val overrides: Map<String, DataValue>) : DataContext {
    override val schema: DataSchema get() = base.schema

    override fun get(path: String): DataValue? = overrides[path] ?: base.get(path)
}

// `page.number` / `page.total`, the layout-derived values the engine overlays on the document data.
fun DataContext.withPage(number: Int, total: Int): DataContext =
    overlay(mapOf("page.number" to DataValue.of(number), "page.total" to DataValue.of(total)))

// --- builder DSL for adapters ---
//
//   val ctx = dataContext {
//       doc {
//           string("designation", "AB.001")
//           decimal("mass", BigDecimal("1.5"))
//           record("customer") { string("name", "ACME") }
//       }
//   }
//
// A null value declares the field only (schema without data, see `dataSchema`).

fun dataContext(block: DataContextBuilder.() -> Unit): DataContext = DataContextBuilder().apply(block).build()

fun dataSchema(block: DataContextBuilder.() -> Unit): DataSchema = dataContext(block).schema

class DataContextBuilder {
    private val roots = LinkedHashMap<String, RecordBuilder>()

    fun doc(block: RecordBuilder.() -> Unit) = root("doc", block)
    fun page(block: RecordBuilder.() -> Unit) = root("page", block)
    fun item(block: RecordBuilder.() -> Unit) = root("item", block)

    private fun root(name: String, block: RecordBuilder.() -> Unit) {
        roots.getOrPut(name) { RecordBuilder() }.apply(block)
    }

    fun build(): DataContext = MapDataContext(
        DataSchema(roots.mapValues { it.value.type() }),
        roots.mapValues { it.value.value() }
    )
}

class RecordBuilder {
    private val types = LinkedHashMap<String, DataType>()
    private val values = LinkedHashMap<String, DataValue>()

    fun string(name: String, value: String? = null) = field(name, DataType.Str, value?.let { DataValue.of(it) })
    fun integer(name: String, value: Long? = null) = field(name, DataType.Integer, value?.let { DataValue.of(it) })
    fun decimal(name: String, value: BigDecimal? = null) = field(name, DataType.Decimal, value?.let { DataValue.of(it) })
    fun date(name: String, value: LocalDate? = null) = field(name, DataType.Date, value?.let { DataValue.of(it) })
    fun bool(name: String, value: Boolean? = null) = field(name, DataType.Bool, value?.let { DataValue.of(it) })

    fun enum(name: String, values: List<String>, value: String? = null) {
        require(value == null || value in values) { "'$value' is not one of $values (field '$name')" }
        field(name, DataType.Enum(values), value?.let { DataValue.Enum(it) })
    }

    fun record(name: String, block: RecordBuilder.() -> Unit) {
        val nested = RecordBuilder().apply(block)
        field(name, nested.type(), nested.value().takeIf { it.fields.isNotEmpty() })
    }

    // Row schema = the union of the rows' fields (first declaration of a field wins).
    fun list(name: String, block: ListBuilder.() -> Unit) {
        val rows = ListBuilder().apply(block).rows
        val schema = DataType.Record(rows.fold(emptyMap<String, DataType>()) { acc, r -> r.type().fields + acc })
        field(name, DataType.ListOf(schema), DataValue.ListOf(rows.map { it.value() }, schema))
    }

    private fun field(name: String, type: DataType, value: DataValue?) {
        types[name] = type
        if (value != null) values[name] = value else values.remove(name)
    }

    internal fun type() = DataType.Record(types.toMap())
    internal fun value() = DataValue.Record(values.toMap())
}

class ListBuilder {
    internal val rows = ArrayList<RecordBuilder>()

    fun row(block: RecordBuilder.() -> Unit) {
        rows += RecordBuilder().apply(block)
    }
}
