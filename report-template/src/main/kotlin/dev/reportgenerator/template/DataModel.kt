package dev.reportgenerator.template

import java.math.BigDecimal
import java.time.LocalDate

// Typed data model a template is bound to. A DataSchema declares what an adapter can supply, DataValue is
// the supplied data, DataContext (DataContext.kt) ties them together. Stage 1 binds scalars only; List and
// Record exist so later stages (collections, repeat.from) build on the same model.

sealed interface DataType {
    val typeName: kotlin.String

    object Str : DataType { override val typeName = "String" }
    object Integer : DataType { override val typeName = "Integer" }
    object Decimal : DataType { override val typeName = "Decimal" }
    object Date : DataType { override val typeName = "Date" }
    object Bool : DataType { override val typeName = "Boolean" }

    data class Enum(val values: List<kotlin.String>) : DataType {
        override val typeName get() = "Enum(${values.joinToString("|")})"
    }

    // A list of records (rows of a collection).
    data class ListOf(val of: Record) : DataType {
        override val typeName get() = "List"
    }

    data class Record(val fields: Map<kotlin.String, DataType>) : DataType {
        override val typeName get() = "Record"
    }

    val isScalar: kotlin.Boolean get() = this !is ListOf && this !is Record
}

// Roots a bind path may start with. `page.number` / `page.total` are supplied by the layout engine.
val DATA_ROOTS: List<String> = listOf("doc", "page", "item")

// Layout-derived fields, always declared in every schema.
val PAGE_FIELDS: Map<String, DataType> = linkedMapOf("number" to DataType.Integer, "total" to DataType.Integer)

class DataSchema(roots: Map<String, DataType.Record> = emptyMap()) {
    // Always exactly doc / page / item. `page` always contains the layout-derived fields.
    val roots: Map<String, DataType.Record> = DATA_ROOTS.associateWith { name ->
        val declared = roots[name]?.fields ?: emptyMap()
        if (name == "page") DataType.Record(declared + PAGE_FIELDS) else DataType.Record(declared)
    }.also { r ->
        val unknown = roots.keys - DATA_ROOTS.toSet()
        require(unknown.isEmpty()) { "unknown schema roots $unknown (allowed: ${DATA_ROOTS.joinToString()})" }
    }

    sealed interface Lookup {
        data class Found(val type: DataType) : Lookup
        data class UnknownRoot(val root: String) : Lookup
        // `parent` = the path that was resolved, `field` = the missing segment, `known` = what parent has.
        data class UnknownField(val parent: String, val field: String, val known: List<String>) : Lookup
        // a segment tried to step into a scalar or a list
        data class NotARecord(val parent: String, val type: DataType) : Lookup
    }

    // `path` without `${}`, e.g. "doc.customer.name".
    fun lookup(path: String): Lookup {
        val parts = path.split('.')
        var current: DataType = roots[parts[0]] ?: return Lookup.UnknownRoot(parts[0])
        for (i in 1 until parts.size) {
            val parent = parts.take(i).joinToString(".")
            val record = current as? DataType.Record ?: return Lookup.NotARecord(parent, current)
            current = record.fields[parts[i]] ?: return Lookup.UnknownField(parent, parts[i], record.fields.keys.toList())
        }
        return Lookup.Found(current)
    }

    fun typeOf(path: String): DataType? = (lookup(path) as? Lookup.Found)?.type

    override fun equals(other: Any?) = other is DataSchema && roots == other.roots
    override fun hashCode() = roots.hashCode()
    override fun toString() = "DataSchema($roots)"
}

sealed interface DataValue {
    val type: DataType

    data class Str(val value: String) : DataValue { override val type get() = DataType.Str }
    data class Integer(val value: Long) : DataValue { override val type get() = DataType.Integer }
    data class Decimal(val value: BigDecimal) : DataValue { override val type get() = DataType.Decimal }
    data class Date(val value: LocalDate) : DataValue { override val type get() = DataType.Date }
    data class Bool(val value: Boolean) : DataValue { override val type get() = DataType.Bool }
    // `type` is only known to the schema: an enum value carries its name
    data class Enum(val name: String) : DataValue { override val type get() = DataType.Enum(listOf(name)) }
    data class ListOf(val items: List<Record>, val of: DataType.Record = DataType.Record(emptyMap())) : DataValue {
        override val type get() = DataType.ListOf(of)
    }
    data class Record(val fields: Map<String, DataValue>) : DataValue {
        override val type get() = DataType.Record(fields.mapValues { it.value.type })
    }

    // Value fits the declared type (enum: name among the declared values).
    fun conformsTo(expected: DataType): Boolean = when (expected) {
        is DataType.Enum -> this is Enum && name in expected.values
        is DataType.ListOf -> this is ListOf
        is DataType.Record -> this is Record
        else -> type == expected
    }

    companion object {
        fun of(value: String) = Str(value)
        fun of(value: Int) = Integer(value.toLong())
        fun of(value: Long) = Integer(value)
        fun of(value: BigDecimal) = Decimal(value)
        fun of(value: LocalDate) = Date(value)
        fun of(value: Boolean) = Bool(value)
    }
}
