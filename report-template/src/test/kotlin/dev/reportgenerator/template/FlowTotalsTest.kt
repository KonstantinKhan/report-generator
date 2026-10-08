package dev.reportgenerator.template

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Stage 4 of the flow table: arithmetic `computed` fields (multiply / add / subtract / divide) and `totals`
// (sum / count / min / max / avg per group and per table): loader, validator, contract, runtime on records.
class FlowTotalsTest {
    private fun yaml(table: String) =
        "sheet: {format: A4, margins: {left: 20, right: 5}}\nblocks:\n  - id: body\n    type: flow\n    table:\n" +
            ("rowHeight: 8\ncolumns: [{id: a, width: 100}, {id: b, width: 85}]\n" + table.trimIndent()).prependIndent("      ")

    private val row = "row: {cells: {a: {bind: \"\${item.name}\"}, b: {bind: \"\${item.cost}\", optional: true}}}"

    private fun spec(extra: String, cells: String = row): FlowTableSpec =
        (TemplateLoader.parse(yaml("${extra.trimIndent()}\n$cells")).blocks.single() as FlowBlock).table!!

    private fun parseError(extra: String): TemplateError =
        assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("${extra.trimIndent()}\n$row")) }.errors.first()

    private fun validate(extra: String) =
        TemplateValidator.validate(TemplateLoader.parse(yaml("${extra.trimIndent()}\n$row"))).associate { it.path to it.message }

    private val kinds = listOf("A", "B", "C")
    private val schema = dataSchema {
        item { string("name"); enum("kind", kinds); integer("qty"); decimal("price"); decimal("weight"); string("note"); integer("n") }
    }

    private fun rec(name: String, kind: String = "A", qty: Long? = 1, price: String? = null, weight: String? = null, n: Long? = null) =
        dataContext {
            item {
                string("name", name); enum("kind", kinds, kind); integer("qty", qty); decimal("price", price?.let(::BigDecimal))
                decimal("weight", weight?.let(::BigDecimal)); string("note"); integer("n", n)
            }
        }.get("item") as DataValue.Record

    private fun contract(spec: FlowTableSpec) = TemplateContract.checkFlowTable(spec, schema, "t").associate { it.path to it.message }

    private fun table(spec: FlowTableSpec, rows: List<DataValue.Record>) = FlowShaper.shapeTable(spec, schema, rows)

    private fun dec(v: DataValue?) = (v as DataValue.Decimal).value

    private val cost = "computed: {cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}}"

    // ---- loader ----

    @Test
    fun `arithmetic computed fields and totals load into the model`() {
        val t = spec(
            """
            computed:
              cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}
              net: {subtract: [cost, 1.5]}
              sum3: {add: [qty, n, 10]}
              per: {divide: [cost, qty], scale: 4, rounding: DOWN}
              pos: {sequence: {}}
            groupBy: {field: kind, order: [A], titles: {A: T}}
            totals:
              - {id: g, scope: group, agg: sum, field: cost, label: "Итого", labelColumn: a, valueColumn: b, format: {pattern: "0.00", locale: ru}, style: totalText}
              - {id: avg, scope: table, agg: avg, field: price, label: "Среднее", labelColumn: a, valueColumn: b, scale: 2, rounding: HALF_EVEN, where: {field: qty, eq: "2"}, skipEmpty: false}
            """
        )
        assertEquals(FlowComputed.Arithmetic(ArithOp.MULTIPLY, listOf(Operand.Field("qty"), Operand.Field("price")), 2, RoundingMode.HALF_UP), t.computed["cost"])
        assertEquals(FlowComputed.Arithmetic(ArithOp.SUBTRACT, listOf(Operand.Field("cost"), Operand.Literal("1.5"))), t.computed["net"])
        assertEquals(listOf(Operand.Field("qty"), Operand.Field("n"), Operand.Literal("10")), (t.computed["sum3"] as FlowComputed.Arithmetic).operands)
        assertEquals(FlowComputed.Sequence(), t.computed["pos"])
        assertEquals(2, t.totals.size)
        assertEquals(
            FlowTotal(
                "g", TotalScope.GROUP, TotalAgg.SUM, "cost", "Итого", "a", "b",
                format = FormatSpec("0.00", "ru"), style = "totalText"
            ),
            t.totals[0]
        )
        assertEquals(
            FlowTotal(
                "avg", TotalScope.TABLE, TotalAgg.AVG, "price", "Среднее", "a", "b",
                where = Predicate.Eq("qty", "2"), skipEmpty = false, scale = 2, rounding = RoundingMode.HALF_EVEN
            ),
            t.totals[1]
        )
    }

    @Test
    fun `totals default to skipEmpty and shape errors carry yaml paths`() {
        assertEquals(true, spec("totals: [{id: c, scope: table, agg: count, label: L, labelColumn: a, valueColumn: b}]").totals.single().skipEmpty)
        assertEquals("blocks[0].table.totals[0].agg", parseError("totals: [{id: c, scope: table, agg: median, label: L, labelColumn: a, valueColumn: b}]").path)
        assertEquals("blocks[0].table.totals[0].scope", parseError("totals: [{id: c, scope: page, agg: count, label: L, labelColumn: a, valueColumn: b}]").path)
        assertEquals("blocks[0].table.totals[0]", parseError("totals: [{id: c, scope: table, agg: count, labelColumn: a, valueColumn: b}]").path)
        assertEquals("blocks[0].table.totals[0].bogus", parseError("totals: [{id: c, scope: table, agg: count, label: L, labelColumn: a, valueColumn: b, bogus: 1}]").path)
        assertEquals("blocks[0].table.totals[0].rounding", parseError("totals: [{id: c, scope: table, agg: avg, label: L, labelColumn: a, valueColumn: b, scale: 2, rounding: AROUND}]").path)
        assertEquals("blocks[0].table.computed.x", parseError("computed: {x: {multiply: [qty, n], add: [qty, n]}}").path)
        assertEquals("blocks[0].table.computed.x", parseError("computed: {x: {scale: 2}}").path)
        assertEquals("blocks[0].table.computed.x.multiply", parseError("computed: {x: {multiply: qty}}").path)
        assertEquals("blocks[0].table.computed.x", parseError("computed: {x: {sequence: {}, scale: 2}}").path)
        assertEquals("blocks[0].table.computed.x.bogus", parseError("computed: {x: {multiply: [qty, n], bogus: 1}}").path)
    }

    // ---- validator ----

    @Test
    fun `validator checks the shape of totals and arithmetic`() {
        val e = validate(
            """
            computed:
              s: {subtract: [qty, n, 1]}
              m: {multiply: [qty]}
              d: {divide: [qty, n], scale: -1, rounding: UP}
            groupBy: {field: kind, order: [A], titles: {A: T}}
            totals:
              - {id: dup, scope: table, agg: count, field: qty, label: L, labelColumn: a, valueColumn: b}
              - {id: dup, scope: table, agg: sum, label: "", labelColumn: zz, valueColumn: zz}
              - {id: avg, scope: group, agg: avg, field: qty, label: L, labelColumn: a, valueColumn: b}
              - {id: mn, scope: table, agg: min, field: qty, label: L, labelColumn: a, valueColumn: b, scale: 2, rounding: UP}
              - {id: "bad id", scope: table, agg: count, label: L, labelColumn: a, valueColumn: b}
            """
        )
        assertTrue("exactly 2 operands" in e.getValue("blocks[0].table.computed.s.subtract"))
        assertTrue("at least 2 operands" in e.getValue("blocks[0].table.computed.m.multiply"))
        assertTrue(">= 0" in e.getValue("blocks[0].table.computed.d.scale"))
        assertTrue("takes no 'field'" in e.getValue("blocks[0].table.totals[0].field"))
        assertTrue("duplicate total id" in e.getValue("blocks[0].table.totals[1].id"))
        assertTrue("needs a 'field'" in e.getValue("blocks[0].table.totals[1]"))
        assertTrue("must not be empty" in e.getValue("blocks[0].table.totals[1].label"))
        assertTrue("unknown column 'zz'" in e.getValue("blocks[0].table.totals[1].labelColumn"))
        assertTrue("different columns" in e.getValue("blocks[0].table.totals[1].valueColumn"))
        // avg needs both scale and rounding (reported once on the entry: scale first, rounding second, same path)
        assertTrue("avg needs" in e.getValue("blocks[0].table.totals[2]"))
        assertTrue("applies to avg only" in e.getValue("blocks[0].table.totals[3].scale"))
        assertTrue("applies to avg only" in e.getValue("blocks[0].table.totals[3].rounding"))
        assertTrue("invalid id" in e.getValue("blocks[0].table.totals[4].id"))
    }

    @Test
    fun `scope group needs groupBy`() {
        val e = validate("totals: [{id: g, scope: group, agg: count, label: L, labelColumn: a, valueColumn: b}]")
        assertTrue("needs 'groupBy'" in e.getValue("blocks[0].table.totals[0].scope"))
    }

    // ---- contract ----

    @Test
    fun `computed result types are inferred`() {
        val t = spec(
            """
            computed:
              ii: {multiply: [qty, n]}
              id: {multiply: [qty, price]}
              lit: {add: [qty, 1]}
              litd: {add: [qty, 1.5]}
              dv: {divide: [qty, n], scale: 3, rounding: HALF_UP}
              chain: {subtract: [ii, lit]}
              seq: {sequence: {}}
            """
        )
        assertEquals(
            mapOf(
                "ii" to DataType.Integer, "id" to DataType.Decimal, "lit" to DataType.Integer, "litd" to DataType.Decimal,
                "dv" to DataType.Decimal, "chain" to DataType.Integer, "seq" to DataType.Integer
            ),
            t.itemFields(schema).filterKeys { it in t.computed }
        )
    }

    @Test
    fun `computed arithmetic reads numeric fields, without cycles, and rounds as its result type needs`() {
        val e = contract(
            spec(
                """
                computed:
                  a: {add: [b, 1]}
                  b: {add: [a, 1]}
                  self: {add: [self, 1]}
                  txt: {add: [name, 1]}
                  nope: {add: [qtyy, 1]}
                  seq: {sequence: {}}
                  fromSeq: {add: [seq, 1]}
                  div: {divide: [qty, n]}
                  intScale: {add: [qty, n], scale: 2, rounding: UP}
                  half: {multiply: [qty, price], scale: 2}
                  name: {add: [qty, 1]}
                """
            )
        )
        assertTrue("cyclic computed dependency" in e.getValue("t.computed.a"), e.toString())
        assertTrue("a -> b -> a" in e.getValue("t.computed.a"), e.toString())
        assertTrue("self -> self" in e.getValue("t.computed.self"), e.toString())
        assertTrue("String, arithmetic needs Integer or Decimal" in e.getValue("t.computed.txt.add[0]"))
        assertTrue("did you mean 'qty'" in e.getValue("t.computed.nope.add[0]"))
        assertTrue("sequence" in e.getValue("t.computed.fromSeq.add[0]"))
        assertTrue("divide needs both 'scale' and 'rounding'" in e.getValue("t.computed.div"))
        assertTrue("result is Integer" in e.getValue("t.computed.intScale.scale"))
        assertTrue("result is Integer" in e.getValue("t.computed.intScale.rounding"))
        assertTrue("go together" in e.getValue("t.computed.half"))
        assertTrue("clashes with a field" in e.getValue("t.computed.name"))
        assertEquals(setOf("a", "self", "txt", "nope", "fromSeq", "div", "intScale", "half", "name").sorted(), e.keys.filter { it.startsWith("t.computed.") }.map { it.removePrefix("t.computed.").substringBefore('.') }.toSet().sorted())
    }

    @Test
    fun `computed fields are usable in sortBy but not in where or groupBy`() {
        val ok = contract(spec("$cost\nsortBy: [{field: cost}]"))
        assertEquals(emptyMap(), ok)
        val e = contract(spec("$cost\nwhere: {field: cost, notNull: true}\ngroupBy: {field: cost, order: [A], titles: {A: T}}"))
        assertTrue("not available here" in e.getValue("t.where.field"))
        assertTrue("not available here" in e.getValue("t.groupBy.field"))
        val seq = contract(spec("computed: {pos: {sequence: {}}}\nsortBy: [{field: pos}]"))
        assertTrue("row order" in seq.getValue("t.sortBy[0].field"))
    }

    @Test
    fun `totals read numeric fields and format their result type`() {
        val e = contract(
            spec(
                """
                $cost
                groupBy: {field: kind, order: [A, B, C], titles: {A: a, B: b, C: c}}
                totals:
                  - {id: t0, scope: group, agg: sum, field: name, label: L, labelColumn: a, valueColumn: b}
                  - {id: t1, scope: group, agg: sum, field: nope, label: L, labelColumn: a, valueColumn: b}
                  - {id: t2, scope: table, agg: count, label: L, labelColumn: a, valueColumn: b, format: {pattern: "0"}}
                  - {id: t3, scope: table, agg: sum, field: cost, label: L, labelColumn: a, valueColumn: b, format: {pattern: "0.#.#"}}
                  - {id: t4, scope: table, agg: min, field: qty, label: L, labelColumn: a, valueColumn: b, format: {pattern: "0"}}
                  - {id: t5, scope: table, agg: avg, field: qty, label: L, labelColumn: a, valueColumn: b, scale: 2, rounding: UP, format: {pattern: "0.00", locale: ru}}
                  - {id: t6, scope: table, agg: sum, field: cost, label: L, labelColumn: a, valueColumn: b, where: {field: nope, isNull: true}}
                  - {id: t7, scope: table, agg: sum, field: cost, label: L, labelColumn: a, valueColumn: b, where: {field: cost, notNull: true}, format: {pattern: "0.00"}}
                  - {id: t8, scope: table, agg: max, field: kind, label: L, labelColumn: a, valueColumn: b}
                """
            )
        )
        assertTrue("needs an Integer or Decimal field" in e.getValue("t.totals[0].field"))
        assertTrue("unknown item field 'nope'" in e.getValue("t.totals[1].field"))
        assertTrue("only Decimal and Date" in e.getValue("t.totals[2].format"), "count is Integer: ${e["t.totals[2].format"]}")
        assertTrue("invalid decimal pattern" in e.getValue("t.totals[3].format"))
        assertTrue("only Decimal and Date" in e.getValue("t.totals[4].format"), "min of Integer is Integer")
        assertTrue("t.totals[5].format" !in e && "t.totals[7].format" !in e, "avg and a computed Decimal sum take a Decimal pattern")
        assertTrue("unknown item field 'nope'" in e.getValue("t.totals[6].where.field"))
        assertTrue("Integer or Decimal" in e.getValue("t.totals[8].field"))
    }

    // ---- runtime: computed arithmetic ----

    @Test
    fun `cost is quantity times price with exact BigDecimal and explicit rounding`() {
        val t = spec(cost)
        val shaped = table(t, listOf(rec("a", qty = 3, price = "0.335"), rec("b", qty = 2, price = "10.5"))).groups.single().rows
        // 3 * 0.335 = 1.005 -> HALF_UP at scale 2 = 1.01 (not the double 1.0049999...); 2 * 10.5 = 21.0 -> 21.00
        assertEquals(BigDecimal("1.01"), dec(shaped[0].fields["cost"]))
        assertEquals(BigDecimal("21.00"), dec(shaped[1].fields["cost"]))
        assertEquals("1.01", dec(shaped[0].fields["cost"]).toPlainString())
    }

    @Test
    fun `arithmetic without scale keeps the exact result, integers stay integers`() {
        val t = spec("computed: {c: {multiply: [qty, price]}, i: {multiply: [qty, n]}, s: {subtract: [qty, 5]}, a: {add: [qty, n, 10]}}")
        val r = table(t, listOf(rec("a", qty = 3, price = "0.335", n = 4))).groups.single().rows.single().fields
        assertEquals(BigDecimal("1.005"), dec(r["c"]))
        assertEquals(DataValue.Integer(12), r["i"])
        assertEquals(DataValue.Integer(-2), r["s"])
        assertEquals(DataValue.Integer(17), r["a"])
    }

    @Test
    fun `divide rounds explicitly, a computed field may read another and sorts`() {
        val t = spec(
            """
            computed:
              cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}
              each: {divide: [cost, qty], scale: 3, rounding: HALF_UP}
            sortBy: [{field: cost, order: desc}]
            """
        )
        val rows = table(t, listOf(rec("x", qty = 3, price = "1.00"), rec("y", qty = 7, price = "2.00"), rec("z", qty = 1, price = "5.00"))).groups.single().rows
        assertEquals(listOf("y", "z", "x"), rows.map { (it.fields["name"] as DataValue.Str).value })
        assertEquals(BigDecimal("2.000"), dec(rows[0].fields["each"]))
    }

    @Test
    fun `a row without an operand value has no computed value`() {
        val t = spec(cost)
        val rows = table(t, listOf(rec("a", qty = 2, price = null), rec("b", qty = 2, price = "1.5"))).groups.single().rows
        assertNull(rows[0].fields["cost"])
        assertEquals(BigDecimal("3.00"), dec(rows[1].fields["cost"]))
    }

    @Test
    fun `division by zero names the source row and its fields`() {
        val t = spec("computed: {each: {divide: [price, qty], scale: 2, rounding: HALF_UP}}")
        val e = assertFailsWith<IllegalStateException> {
            table(t, listOf(rec("ok", qty = 2, price = "4"), rec("Болт", qty = 0, price = "4.5")))
        }
        val message = e.message!!
        assertTrue("computed 'each' (divide): division by zero" in message, message)
        assertTrue("source row 2" in message && "name=Болт" in message && "qty=0" in message && "price=4.5" in message, message)
    }

    @Test
    fun `where drops a row before its arithmetic runs, integer overflow is an error`() {
        val t = spec("where: {field: qty, ne: \"0\"}\ncomputed: {each: {divide: [price, qty], scale: 2, rounding: HALF_UP}}")
        assertEquals(1, table(t, listOf(rec("z", qty = 0, price = "1"), rec("k", qty = 4, price = "1"))).groups.single().rows.size)
        val big = spec("computed: {sq: {multiply: [n, n]}}")
        val e = assertFailsWith<IllegalStateException> { table(big, listOf(rec("o", n = Long.MAX_VALUE))) }
        assertTrue("Integer overflow" in e.message!! && "source row 1" in e.message!!, e.message)
    }

    // ---- runtime: totals ----

    private val grouped = "groupBy: {field: kind, order: [A, B, C], titles: {A: a, B: b, C: c}, skipEmpty: false}"
    private fun totalOf(id: String, agg: String, field: String?, scope: String = "group", extra: String = "") =
        "{id: $id, scope: $scope, agg: $agg, ${field?.let { "field: $it, " }.orEmpty()}label: \"L $id\", labelColumn: a, valueColumn: b$extra}"

    @Test
    fun `sum is exact BigDecimal and keeps the field's scale`() {
        val t = spec("$cost\n$grouped\ntotals: [${totalOf("s", "sum", "price", "group", ", skipEmpty: false")}, ${totalOf("t", "sum", "price", "table")}, ${totalOf("c", "sum", "qty", "table")}]")
        val rows = listOf(rec("a", "A", qty = 1, price = "0.1"), rec("b", "A", qty = 2, price = "0.2"), rec("c", "B", qty = 4, price = "0.30"))
        val shaped = table(t, rows)
        // 0.1 + 0.2 is exactly 0.3 (double arithmetic would give 0.30000000000000004)
        assertEquals(BigDecimal("0.3"), dec(shaped.groups[0].totals.single().value))
        assertEquals(BigDecimal("0.30"), dec(shaped.groups[1].totals.single().value))
        assertEquals(listOf("0.3", "0.30", "0"), shaped.groups.map { it.totals.single().text })
        assertEquals(BigDecimal("0.60"), dec(shaped.totals[0].value))
        // an Integer field sums to Integer
        assertEquals(DataValue.Integer(7), shaped.totals[1].value)
    }

    @Test
    fun `count, min, max and avg with explicit scale and rounding`() {
        val totals = listOf(
            totalOf("n", "count", null, "table"),
            totalOf("mn", "min", "price", "table", ", format: {pattern: \"0.00\", locale: ru}"),
            totalOf("mx", "max", "price", "table"),
            totalOf("av", "avg", "price", "table", ", scale: 2, rounding: HALF_UP, format: {pattern: \"0.00\", locale: ru}"),
            totalOf("av0", "avg", "price", "table", ", scale: 0, rounding: DOWN"),
            totalOf("mq", "max", "qty", "table")
        ).joinToString()
        val t = spec("totals: [$totals]")
        val rows = listOf(rec("a", qty = 2, price = "10"), rec("b", qty = 9, price = "20"), rec("c", qty = 5, price = "5"), rec("d", qty = 1, price = null))
        val r = table(t, rows).totals.associateBy { it.total.id }
        assertEquals(DataValue.Integer(4), r.getValue("n").value, "count counts rows, also the ones without price")
        assertEquals("5,00", r.getValue("mn").text)
        assertEquals(BigDecimal("20"), dec(r.getValue("mx").value))
        // (10 + 20 + 5) / 3 = 11.666... over the 3 rows that have a price; HALF_UP scale 2 = 11.67; DOWN scale 0 = 11
        assertEquals("11,67", r.getValue("av").text)
        assertEquals("11", r.getValue("av0").text)
        assertEquals(DataValue.Integer(9), r.getValue("mq").value)
    }

    @Test
    fun `min, max and avg of no values have no value and an empty text, sum is zero and count is zero`() {
        val t = spec("totals: [${listOf("sum", "min", "max").joinToString { totalOf(it, it, "price", "table") }}, ${totalOf("av", "avg", "price", "table", ", scale: 2, rounding: UP")}, ${totalOf("n", "count", null, "table")}]")
        val r = table(t, listOf(rec("a", price = null))).totals.associateBy { it.total.id }
        assertEquals(BigDecimal.ZERO, dec(r.getValue("sum").value))
        assertEquals("0", r.getValue("sum").text)
        assertNull(r.getValue("min").value); assertEquals("", r.getValue("min").text)
        assertNull(r.getValue("max").value)
        assertNull(r.getValue("av").value); assertEquals("", r.getValue("av").text)
        assertEquals("1", r.getValue("n").text)
    }

    @Test
    fun `where limits the rows of an aggregate, not the table`() {
        val t = spec("$grouped\ntotals: [${totalOf("n", "count", null, "group", ", where: {field: qty, in: [\"2\", \"3\"]}")}, ${totalOf("s", "sum", "qty", "table", ", where: {not: {field: kind, eq: C}}")}]")
        val rows = listOf(rec("a", "A", qty = 2), rec("b", "A", qty = 1), rec("c", "B", qty = 3), rec("d", "C", qty = 30))
        val shaped = table(t, rows)
        assertEquals(listOf(1L, 1L, 0L), shaped.groups.map { ((it.totals.single().value) as DataValue.Integer).value })
        assertEquals(listOf(2, 1, 1), shaped.groups.map { it.rows.size }, "all rows stay in the table")
        assertEquals(DataValue.Integer(6), shaped.totals.single().value, "2 + 1 + 3: the where drops kind C from this sum only")
    }

    @Test
    fun `a group total of an empty group is skipped unless skipEmpty is false`() {
        val rows = listOf(rec("a", "A", qty = 2))
        val skip = table(spec("$grouped\ntotals: [${totalOf("n", "count", null)}]"), rows).groups
        assertEquals(listOf(1, 0, 0), skip.map { it.totals.size }, "groups B and C are empty: no total")
        val keep = table(spec("$grouped\ntotals: [${totalOf("n", "count", null, "group", ", skipEmpty: false")}]"), rows).groups
        assertEquals(listOf("1", "0", "0"), keep.map { it.totals.single().text })
        // table scope: no rows at all
        assertEquals(0, table(spec("totals: [${totalOf("n", "count", null, "table")}]"), emptyList()).totals.size)
        assertEquals(listOf("0"), table(spec("totals: [${totalOf("n", "count", null, "table", ", skipEmpty: false")}]"), emptyList()).totals.map { it.text })
    }

    @Test
    fun `table totals cover the rows that are drawn, omitted values are not counted`() {
        val t = spec("groupBy: {field: kind, order: [A, B], titles: {A: a, B: b}, omit: [C]}\ntotals: [${totalOf("n", "count", null, "table")}]")
        val shaped = table(t, listOf(rec("a", "A"), rec("b", "B"), rec("c", "C"), rec("d", "C")))
        assertEquals("2", shaped.totals.single().text)
    }

    @Test
    fun `shape without totals is unchanged and group totals keep the spec order`() {
        val t = spec("$grouped\ntotals: [${totalOf("n", "count", null)}, ${totalOf("s", "sum", "qty")}, ${totalOf("g", "count", null, "table")}]")
        val shaped = table(t, listOf(rec("a", "A", qty = 5)))
        assertEquals(listOf("n", "s"), shaped.groups[0].totals.map { it.total.id })
        assertEquals(listOf("g"), shaped.totals.map { it.total.id })
        assertEquals(1, FlowShaper.shape(t, schema, listOf(rec("a", "A"))).count { it.rows.isNotEmpty() })
    }
}
