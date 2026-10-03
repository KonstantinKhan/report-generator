package dev.reportgenerator.template

import java.math.BigDecimal

// Arithmetic `computed` fields of a flow table (multiply / add / subtract / divide, see FlowComputed.Arithmetic): type
// inference with dependency order and cycle check (the contract), and the exact evaluation on one row (the shaper).
// Closed operations only, no expression language. A `sequence` depends on the row order, so it cannot be an operand
// (the order itself may depend on an arithmetic field through `sortBy`).

// `types`: the type of every computed field that resolved (sequences: Integer). `order`: arithmetic fields so that
// every field comes after the ones it reads. `errors`: what failed, with YAML paths under `path`.
internal class ComputedTypes(val types: Map<String, DataType>, val order: List<String>, val errors: List<TemplateError>)

private val NUMERIC: Set<DataType> = setOf(DataType.Integer, DataType.Decimal)

internal fun FlowTableSpec.computedTypes(raw: Map<String, DataType>, path: String = "table"): ComputedTypes {
    val types = LinkedHashMap<String, DataType>()
    val order = ArrayList<String>()
    val errors = ArrayList<TemplateError>()
    val failed = HashSet<String>()
    computed.forEach { (name, op) -> if (op is FlowComputed.Sequence) types[name] = DataType.Integer }

    fun resolve(name: String, stack: List<String>): DataType? {
        types[name]?.let { return it }
        if (name in failed) return null
        if (name in stack) {
            val cycle = (stack.dropWhile { it != name } + name).joinToString(" -> ")
            errors += TemplateError("$path.computed.$name", "cyclic computed dependency: $cycle")
            return null
        }
        val op = computed.getValue(name) as FlowComputed.Arithmetic
        val base = "$path.computed.$name.${op.op.key}"
        val operandTypes = op.operands.mapIndexed { i, operand ->
            val at = "$base[$i]"
            when (operand) {
                is Operand.Literal ->
                    if (operand.text.contains('.')) DataType.Decimal else operand.text.toLongOrNull()?.let { DataType.Integer } ?: DataType.Decimal
                is Operand.Field -> {
                    val n = operand.name
                    val type = when {
                        n in raw -> raw.getValue(n).also {
                            if (it !in NUMERIC) errors += TemplateError(at, "item field '$n' is ${it.typeName}, arithmetic needs Integer or Decimal")
                        }.takeIf { it in NUMERIC }
                        computed[n] is FlowComputed.Arithmetic -> resolve(n, stack + name)
                        computed[n] is FlowComputed.Sequence -> {
                            errors += TemplateError(at, "computed field '$n' is a sequence (depends on the row order) and cannot be an operand")
                            null
                        }
                        else -> {
                            val known = raw.keys + computed.filterValues { it is FlowComputed.Arithmetic }.keys
                            val hint = TemplateContract.nearest(n, known.toList())?.let { ", did you mean '$it'?" }.orEmpty()
                            errors += TemplateError(at, "unknown item field '$n' (${known.ifEmpty { setOf("none") }.joinToString()})$hint")
                            null
                        }
                    }
                    type
                }
            }
        }
        if (operandTypes.any { it == null }) {
            failed += name
            return null
        }
        val result = if (op.op == ArithOp.DIVIDE || DataType.Decimal in operandTypes) DataType.Decimal else DataType.Integer
        types[name] = result
        order += name
        return result
    }

    computed.forEach { (name, op) -> if (op is FlowComputed.Arithmetic) resolve(name, emptyList()).also { if (it == null) failed += name } }
    return ComputedTypes(types, order, errors)
}

internal object FlowArithmetic {
    // Value of `op` on a row: raw fields come from `row`, other arithmetic fields from `known` (evaluated earlier, in
    // dependency order). null when an operand has no value. `where` describes the row for the error of a failed
    // calculation (division by zero, Integer overflow). `result` = the inferred type (computedTypes).
    fun evaluate(name: String, op: FlowComputed.Arithmetic, result: DataType, row: DataValue.Record, known: Map<String, DataValue>, where: () -> String): DataValue? {
        val values = op.operands.map { operand ->
            when (operand) {
                is Operand.Literal -> operand.text.toLongOrNull()?.let { DataValue.Integer(it) } ?: DataValue.Decimal(BigDecimal(operand.text))
                is Operand.Field -> row.fields[operand.name] ?: known[operand.name] ?: return null
            }
        }
        fun fail(why: String): Nothing = error("computed '$name' (${op.op.key}): $why, ${where()}")
        if (result == DataType.Integer) {
            val longs = values.map { (it as? DataValue.Integer ?: fail("${it.type.typeName} is not an Integer")).value }
            return try {
                DataValue.Integer(
                    when (op.op) {
                        ArithOp.MULTIPLY -> longs.reduce(Math::multiplyExact)
                        ArithOp.ADD -> longs.reduce(Math::addExact)
                        else -> Math.subtractExact(longs[0], longs[1])
                    }
                )
            } catch (e: ArithmeticException) {
                fail("Integer overflow")
            }
        }
        val numbers = values.map { v ->
            when (v) {
                is DataValue.Integer -> BigDecimal.valueOf(v.value)
                is DataValue.Decimal -> v.value
                else -> fail("${v.type.typeName} is not a number")
            }
        }
        val exact = when (op.op) {
            ArithOp.MULTIPLY -> numbers.reduce(BigDecimal::multiply)
            ArithOp.ADD -> numbers.reduce(BigDecimal::add)
            ArithOp.SUBTRACT -> numbers[0].subtract(numbers[1])
            ArithOp.DIVIDE -> {
                if (numbers[1].signum() == 0) fail("division by zero")
                numbers[0].divide(numbers[1], op.scale!!, op.rounding!!)
            }
        }
        return DataValue.Decimal(if (op.op != ArithOp.DIVIDE && op.scale != null) exact.setScale(op.scale, op.rounding!!) else exact)
    }

    // "name=Болт, qty=3, price=0": the scalar fields of a row, to tell which row failed.
    fun describe(row: DataValue.Record, sourceIndex: Int): String =
        "source row ${sourceIndex + 1} {" + row.fields.entries.joinToString(", ") { (k, v) -> "$k=${brief(v)}" } + "}"

    private fun brief(v: DataValue): String = when (v) {
        is DataValue.Str -> v.value
        is DataValue.Integer -> v.value.toString()
        is DataValue.Decimal -> v.value.toPlainString()
        is DataValue.Date -> v.value.toString()
        is DataValue.Bool -> v.value.toString()
        is DataValue.Enum -> v.name
        is DataValue.ListOf, is DataValue.Record -> v.type.typeName
    }
}
