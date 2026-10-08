package dev.reportgenerator.template

import java.math.BigDecimal

// `totals:` of a flow table (FlowTotal): result type of an aggregate and its evaluation over the rows of a scope.
// BigDecimal arithmetic, nothing is rounded except `avg` (explicit scale + rounding). A row without a value for the
// field does not take part (min / max / avg / sum); `count` counts rows. Contract: FlowContract.

// What a total gives: Integer for `count`, the field's type for sum / min / max, Decimal for `avg`.
internal fun FlowTotal.resultType(fieldType: DataType?): DataType = when (agg) {
    TotalAgg.COUNT -> DataType.Integer
    TotalAgg.AVG -> DataType.Decimal
    else -> requireNotNull(fieldType) { "total '$id' (${agg.key}) has no numeric field" }
}

// One evaluated total: `value` is null when there is nothing to aggregate (min / max / avg over no values), `text`
// is the cell text (format applied, empty for no value).
class TotalValue(val total: FlowTotal, val value: DataValue?, val text: String)

// Totals compiled against the field types (`fields` = record fields plus computed ones).
internal class FlowTotals(totals: List<FlowTotal>, private val fields: Map<String, DataType>) {
    private class Compiled(val total: FlowTotal, val type: DataType?, val keep: ((DataValue.Record) -> Boolean)?)

    private val compiled = totals.map { t ->
        Compiled(t, t.field?.let { fields[it] }, t.where?.let { compilePredicate(it, fields) })
    }

    fun group(rows: List<DataValue.Record>): List<TotalValue> = evaluate(TotalScope.GROUP, rows)

    fun table(rows: List<DataValue.Record>): List<TotalValue> = evaluate(TotalScope.TABLE, rows)

    private fun evaluate(scope: TotalScope, rows: List<DataValue.Record>): List<TotalValue> =
        compiled.filter { it.total.scope == scope }.mapNotNull { c ->
            if (rows.isEmpty() && c.total.skipEmpty) return@mapNotNull null
            val included = c.keep?.let { keep -> rows.filter(keep) } ?: rows
            val value = aggregate(c.total, c.type, included)
            TotalValue(c.total, value, value?.let { ValueFormatter.render(it, c.total.format) }.orEmpty())
        }

    private fun aggregate(t: FlowTotal, type: DataType?, rows: List<DataValue.Record>): DataValue? {
        if (t.agg == TotalAgg.COUNT) return DataValue.Integer(rows.size.toLong())
        val values = rows.mapNotNull { it.fields[t.field!!] }
        return when (t.agg) {
            TotalAgg.SUM -> if (type == DataType.Integer) {
                DataValue.Integer(values.fold(0L) { acc, v ->
                    try { Math.addExact(acc, (v as DataValue.Integer).value) } catch (e: ArithmeticException) { error("total '${t.id}': Integer overflow") }
                })
            } else DataValue.Decimal(values.fold(BigDecimal.ZERO) { acc, v -> acc.add(number(v)) })
            TotalAgg.MIN -> values.minWithOrNull { a, b -> compareValues(type!!, a, b) }
            TotalAgg.MAX -> values.maxWithOrNull { a, b -> compareValues(type!!, a, b) }
            TotalAgg.AVG ->
                if (values.isEmpty()) null
                else DataValue.Decimal(values.fold(BigDecimal.ZERO) { acc, v -> acc.add(number(v)) }
                    .divide(BigDecimal(values.size), t.scale!!, t.rounding!!))
            TotalAgg.COUNT -> error("unreachable")
        }
    }

    private fun number(v: DataValue): BigDecimal = when (v) {
        is DataValue.Integer -> BigDecimal.valueOf(v.value)
        is DataValue.Decimal -> v.value
        else -> error("${v.type.typeName} is not a number")
    }
}
