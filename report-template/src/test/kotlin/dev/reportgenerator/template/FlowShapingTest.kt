package dev.reportgenerator.template

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Data rules of a flow table: loader (shape, paths), validator, contract (types, enum members, coverage) and the
// runtime shaping (where / sortBy / groupBy / computed / cases / format).
class FlowShapingTest {
    private fun yaml(table: String) =
        "sheet: {format: A4, margins: {left: 20, right: 5}}\nblocks:\n  - id: body\n    type: flow\n    table:\n" +
            ("rowHeight: 8\ncolumns: [{id: a, width: 185}]\n" + table.trimIndent()).prependIndent("      ")

    private val row = "row: {cells: {a: {bind: \"\${item.name}\"}}}"

    private fun spec(extra: String, cells: String = row): FlowTableSpec =
        (TemplateLoader.parse(yaml("${extra.trimIndent()}\n$cells")).blocks.single() as FlowBlock).table!!

    private fun parseError(extra: String): TemplateError =
        assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("${extra.trimIndent()}\n$row")) }.errors.single()

    private fun validate(extra: String) = TemplateValidator.validate(TemplateLoader.parse(yaml("${extra.trimIndent()}\n$row"))).associate { it.path to it.message }

    private val kinds = listOf("A", "B", "C")
    private val schema = dataSchema {
        item { string("name"); enum("kind", kinds); decimal("qty"); string("note"); integer("n"); bool("flag"); date("day") }
    }

    private fun rec(name: String, kind: String? = "A", qty: String? = "1", note: String? = null, n: Long? = null, flag: Boolean? = null) =
        dataContext {
            item {
                string("name", name); enum("kind", kinds, kind); decimal("qty", qty?.let(::BigDecimal))
                string("note", note); integer("n", n); bool("flag", flag); date("day")
            }
        }.get("item") as DataValue.Record

    private fun names(groups: List<ShapedGroup>) = groups.map { g -> g.title to g.rows.map { (it.fields["name"] as DataValue.Str).value } }

    private fun contract(spec: FlowTableSpec) = TemplateContract.checkFlowTable(spec, schema, "t").associate { it.path to it.message }

    // ---- loader ----

    @Test
    fun `shaping sections load into the model`() {
        val t = spec(
            """
            where: {and: [{field: kind, in: [A, B]}, {not: {field: note, isNull: true}}]}
            sortBy: [{field: name, order: desc, nulls: first}, {field: n}]
            groupBy: {field: kind, order: [A, B], titles: {A: "Ах", B: "Бэ"}, skipEmpty: false, omit: [C]}
            computed: {pos: {sequence: {scope: group, start: 10, step: 5}}}
            """
        )
        assertEquals(
            Predicate.And(listOf(Predicate.In("kind", listOf("A", "B")), Predicate.Not(Predicate.IsNull("note")))),
            t.where
        )
        assertEquals(listOf(FlowSort("name", SortOrder.DESC, NullsOrder.FIRST), FlowSort("n", SortOrder.ASC, NullsOrder.LAST)), t.sortBy)
        assertEquals(FlowGroupBy("kind", listOf("A", "B"), mapOf("A" to "Ах", "B" to "Бэ"), skipEmpty = false, omit = listOf("C")), t.groupBy)
        assertEquals(mapOf("pos" to FlowComputed.Sequence(SequenceScope.GROUP, 10, 5)), t.computed)
    }

    @Test
    fun `defaults are skipEmpty, asc, nulls last, table scope from 1 step 1`() {
        val t = spec("groupBy: {field: kind, order: [A], titles: {A: T}}\nsortBy: [{field: n}]\ncomputed: {p: {sequence: {}}}")
        assertEquals(true, t.groupBy?.skipEmpty)
        assertEquals(FlowSort("n"), t.sortBy.single())
        assertEquals(FlowComputed.Sequence(), t.computed["p"])
    }

    @Test
    fun `predicate and cases shape errors carry yaml paths`() {
        assertEquals("blocks[0].table.where", parseError("where: {field: kind, eq: A, ne: B}").path)
        assertEquals("blocks[0].table.where", parseError("where: {field: kind}").path)
        assertEquals("blocks[0].table.where", parseError("where: {eq: A}").path)
        assertEquals("blocks[0].table.where.bogus", parseError("where: {field: kind, bogus: A}").path)
        assertEquals("blocks[0].table.where", parseError("where: {and: [], field: kind}").path)
        assertEquals("blocks[0].table.where.and[1].isNull", parseError("where: {and: [{field: a, notNull: true}, {field: b, isNull: false}]}").path)
        assertEquals("blocks[0].table.where.not", parseError("where: {not: {field: kind, eq: ~}}").path)
        assertEquals("blocks[0].table.sortBy[0].order", parseError("sortBy: [{field: n, order: up}]").path)
        assertEquals("blocks[0].table.sortBy[1].nulls", parseError("sortBy: [{field: n}, {field: n, nulls: middle}]").path)
        assertEquals("blocks[0].table.groupBy.skipEmpty", parseError("groupBy: {field: k, order: [A], skipEmpty: maybe}").path)
        assertEquals("blocks[0].table.groupBy.bogus", parseError("groupBy: {field: k, order: [A], bogus: 1}").path)
        assertEquals("blocks[0].table.computed.p.expr", parseError("computed: {p: {expr: \"1+1\"}}").path)
        assertEquals("blocks[0].table.computed.p.sequence.scope", parseError("computed: {p: {sequence: {scope: page}}}").path)
        assertEquals("blocks[0].table.computed.p.sequence.step", parseError("computed: {p: {sequence: {step: 1.5}}}").path)
        assertEquals(
            "blocks[0].table.row.cells.a.cases[0]",
            assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("row: {cells: {a: {cases: [{bind: \"\${item.name}\"}]}}}")) }.errors.single().path
        )
    }

    // ---- validator ----

    @Test
    fun `validator checks groupBy, computed and cases structure`() {
        val e = validate(
            """
            groupBy: {field: kind, order: [A, A, B], titles: {A: x, Z: y}, omit: [B]}
            groupTitle: {column: a}
            computed: {"bad name": {sequence: {step: 0}}}
            where: {or: []}
            """
        )
        assertTrue("duplicate value 'A'" in e.getValue("blocks[0].table.groupBy.order"))
        assertTrue("also in 'order'" in e.getValue("blocks[0].table.groupBy.omit"))
        assertTrue("not in 'order'" in e.getValue("blocks[0].table.groupBy.titles.Z"))
        assertTrue(e.containsKey("blocks[0].table.groupBy.titles"), "missing title for B")
        assertTrue("must not be empty" in e.getValue("blocks[0].table.where.or"))
        assertTrue(e.containsKey("blocks[0].table.computed.bad name"))
        assertTrue("must not be 0" in e.getValue("blocks[0].table.computed.bad name.sequence.step"))

        val noGroups = validate("groupTitle: {column: a}")
        assertTrue("needs 'groupBy'" in noGroups.getValue("blocks[0].table.groupTitle"))

        val cases = TemplateValidator.validate(
            TemplateLoader.parse(
                yaml("row: {cells: {a: {cases: [{where: {field: kind, eq: A}, text: x, bind: \"\${item.name}\"}, {where: {field: kind, eq: B}, bind: \"\${doc.name}\", format: {pattern: \"0\"}}, {where: {field: kind, eq: C}, text: y, optional: true}]}}}")
            )
        ).associate { it.path to it.message }
        assertTrue("both 'text' and 'bind'" in cases.getValue("blocks[0].table.row.cells.a.cases[0]"))
        assertTrue("expected \${item." in cases.getValue("blocks[0].table.row.cells.a.cases[1].bind"))
        assertTrue("'optional' needs 'bind'" in cases.getValue("blocks[0].table.row.cells.a.cases[2].optional"))
    }

    // ---- contract ----

    @Test
    fun `contract accepts a consistent spec`() {
        val t = spec(
            """
            where: {and: [{field: kind, ne: C}, {field: qty, eq: "1.50"}, {field: flag, eq: true}, {field: day, eq: 2026-01-31}, {field: n, in: [1, 2]}]}
            sortBy: [{field: kind}, {field: qty, order: desc}]
            groupBy: {field: kind, order: [B, A], titles: {A: a, B: b}, omit: [C]}
            computed: {pos: {sequence: {}}}
            """,
            "row: {cells: {a: {cases: [{where: {field: pos, eq: 1}, bind: \"\${item.pos}\"}], bind: \"\${item.qty}\", format: {pattern: \"0.#\"}}}}"
        )
        assertEquals(emptyMap(), contract(t))
    }

    @Test
    fun `contract reports unknown fields, bad literals and non enum groupBy`() {
        val e = contract(
            spec(
                """
                where: {and: [{field: kynd, eq: A}, {field: kind, eq: Z}, {field: n, eq: x}, {field: qty, in: [1, one]}, {field: day, ne: 31.01.2026}]}
                sortBy: [{field: nope}]
                groupBy: {field: name, order: [A], titles: {A: a}}
                """
            )
        )
        assertTrue("did you mean 'kind'" in e.getValue("t.where.and[0].field"), e.toString())
        assertTrue("is not one of A|B|C" in e.getValue("t.where.and[1].eq"))
        assertTrue("not a valid Integer" in e.getValue("t.where.and[2].eq"))
        assertTrue("not a valid Decimal" in e.getValue("t.where.and[3].in[1]"))
        assertTrue("not a valid Date" in e.getValue("t.where.and[4].ne"))
        assertTrue("unknown item field 'nope'" in e.getValue("t.sortBy[0].field"))
        assertTrue("needs an Enum field" in e.getValue("t.groupBy.field"))
    }

    @Test
    fun `groupBy must list every enum value in order or omit, and only members`() {
        val e = contract(spec("groupBy: {field: kind, order: [A, Q], titles: {A: a, Q: q, R: r}, omit: [W]}"))
        assertTrue("not a value of 'kind'" in e.getValue("t.groupBy.order[1]"))
        assertTrue("not a value of 'kind'" in e.getValue("t.groupBy.omit[0]"))
        assertTrue("not a value of 'kind'" in e.getValue("t.groupBy.titles.R"))
        assertTrue("B|C" in e.getValue("t.groupBy.order"), "uncovered B and C: ${e["t.groupBy.order"]}")
        // dropping on purpose is explicit and fine
        assertEquals(emptyMap(), contract(spec("groupBy: {field: kind, order: [A], titles: {A: a}, omit: [B, C]}")))
    }

    @Test
    fun `computed names clash with record fields and are not visible to where, sortBy, groupBy`() {
        val e = contract(spec("computed: {name: {sequence: {}}, pos: {sequence: {}}}\nwhere: {field: pos, eq: 1}\nsortBy: [{field: pos}]"))
        assertTrue("clashes with a field of item" in e.getValue("t.computed.name"))
        assertTrue("not available here" in e.getValue("t.where.field"))
        assertTrue("not available here" in e.getValue("t.sortBy[0].field"))
    }

    @Test
    fun `cell binds and formats are checked, computed fields are Integer`() {
        val e = contract(
            spec(
                "computed: {pos: {sequence: {}}}",
                "row: {cells: {a: {bind: \"\${item.pos}\", format: {pattern: \"0\"}, cases: [{where: {field: kind, eq: Z}, bind: \"\${item.qty}\", format: {pattern: \"0.#.#\"}}, {where: {field: nope, isNull: true}, bind: \"\${item.nope}\"}]}}}"
            )
        )
        assertTrue("only Decimal and Date" in e.getValue("t.row.cells.a.format"))
        assertTrue("is not one of" in e.getValue("t.row.cells.a.cases[0].where.eq"))
        assertTrue("invalid decimal pattern" in e.getValue("t.row.cells.a.cases[0].format"))
        assertTrue(e.containsKey("t.row.cells.a.cases[1].where.field"))
        assertTrue(e.containsKey("t.row.cells.a.cases[1].bind"))
    }

    // ---- runtime: where ----

    private val sample = listOf(
        rec("a1", "A", "1", note = "x", n = 3, flag = true),
        rec("b1", "B", "2", note = null, n = 1, flag = false),
        rec("c1", "C", "3", note = "y", n = null, flag = null),
        rec("a2", "A", "4", note = null, n = 2, flag = true)
    )

    private fun shape(extra: String) = FlowShaper.shape(spec(extra), schema, sample)

    @Test
    fun `where operators and combinators`() {
        fun kept(where: String) = names(shape("where: $where")).single().second
        assertEquals(listOf("a1", "a2"), kept("{field: kind, eq: A}"))
        assertEquals(listOf("b1", "c1"), kept("{field: kind, ne: A}"))
        assertEquals(listOf("b1", "c1"), kept("{field: kind, in: [B, C]}"))
        assertEquals(listOf("b1", "a2"), kept("{field: note, isNull: true}"))
        assertEquals(listOf("a1", "c1"), kept("{field: note, notNull: true}"))
        assertEquals(listOf("c1"), kept("{field: n, isNull: true}"))
        assertEquals(listOf("a1", "a2"), kept("{field: flag, eq: true}"))
        assertEquals(listOf("a1"), kept("{and: [{field: kind, eq: A}, {field: note, notNull: true}]}"))
        assertEquals(listOf("a1", "b1", "a2"), kept("{or: [{field: kind, eq: A}, {field: n, eq: 1}]}"))
        assertEquals(listOf("b1", "c1"), kept("{not: {field: kind, eq: A}}"))
        assertEquals(listOf("b1"), kept("{and: [{not: {field: kind, eq: A}}, {or: [{field: n, eq: 1}, {field: n, eq: 9}]}]}"))
        // numeric equality ignores scale: 1 == 1.00
        assertEquals(listOf("a1"), kept("{field: qty, eq: \"1.00\"}"))
        // a missing value: ne is true, eq and in are false
        assertEquals(listOf("a1", "b1", "c1", "a2"), kept("{field: n, ne: 99}"))
        assertEquals(emptyList(), kept("{field: n, in: [99]}"))
    }

    // ---- runtime: sortBy ----

    @Test
    fun `no sortBy keeps the source order`() = assertEquals(listOf("a1", "b1", "c1", "a2"), names(shape("")).single().second)

    @Test
    fun `sortBy is stable, supports desc and nulls first or last`() {
        fun sorted(sort: String) = names(shape("sortBy: $sort")).single().second
        assertEquals(listOf("a1", "a2", "b1", "c1"), sorted("[{field: kind}]"), "stable inside equal kinds")
        assertEquals(listOf("c1", "b1", "a1", "a2"), sorted("[{field: kind, order: desc}]"), "stable inside equal kinds, desc")
        assertEquals(listOf("b1", "a2", "a1", "c1"), sorted("[{field: n}]"), "nulls last by default")
        assertEquals(listOf("c1", "b1", "a2", "a1"), sorted("[{field: n, nulls: first}]"))
        assertEquals(listOf("a1", "a2", "b1", "c1"), sorted("[{field: n, order: desc}]"), "desc keeps nulls last")
        assertEquals(listOf("c1", "a1", "a2", "b1"), sorted("[{field: n, order: desc, nulls: first}]"))
        // second key breaks ties of the first
        assertEquals(listOf("a2", "a1", "b1", "c1"), sorted("[{field: kind}, {field: qty, order: desc}]"))
        // Decimal numerically, not as text
        val rows = listOf(rec("x", qty = "10"), rec("y", qty = "9"), rec("z", qty = "9.5"))
        assertEquals(listOf("y", "z", "x"), names(FlowShaper.shape(spec("sortBy: [{field: qty}]"), schema, rows)).single().second)
    }

    @Test
    fun `strings sort naturally in Russian alphabet order, case ignored`() {
        val rows = listOf("Вал 10", "вал 2", "Болт", "Ось", "Вал 1", "Арка").map { rec(it) }
        val out = names(FlowShaper.shape(spec("sortBy: [{field: name}]"), schema, rows)).single().second
        assertEquals(listOf("Арка", "Болт", "Вал 1", "вал 2", "Вал 10", "Ось"), out)
    }

    @Test
    fun `enum sorts by the declared order of its values`() {
        val rows = listOf(rec("c", "C"), rec("a", "A"), rec("b", "B"))
        assertEquals(listOf("a", "b", "c"), names(FlowShaper.shape(spec("sortBy: [{field: kind}]"), schema, rows)).single().second)
    }

    // ---- runtime: groupBy ----

    private val grouping = "groupBy: {field: kind, order: [C, A, B], titles: {A: Аа, B: Бб, C: Цц}}"

    @Test
    fun `groupBy follows order, keeps the source order inside, skips empty groups`() {
        assertEquals(listOf("Цц" to listOf("c1"), "Аа" to listOf("a1", "a2"), "Бб" to listOf("b1")), names(shape(grouping)))
        val onlyA = FlowShaper.shape(spec(grouping), schema, sample.filter { it.fields["kind"] == DataValue.Enum("A") })
        assertEquals(listOf("Аа"), onlyA.map { it.title })
        assertEquals(emptyList(), FlowShaper.shape(spec(grouping), schema, emptyList()))
    }

    @Test
    fun `skipEmpty false keeps an empty group, omit drops values on purpose`() {
        val keep = FlowShaper.shape(spec("groupBy: {field: kind, order: [A, B, C], titles: {A: a, B: b, C: c}, skipEmpty: false}"), schema, sample.take(1))
        assertEquals(listOf("a" to listOf("a1"), "b" to emptyList(), "c" to emptyList()), names(keep))
        val omit = shape("groupBy: {field: kind, order: [A], titles: {A: a}, omit: [B, C]}")
        assertEquals(listOf("a" to listOf("a1", "a2")), names(omit))
    }

    @Test
    fun `where and sortBy apply before grouping`() {
        val out = shape("where: {field: n, notNull: true}\nsortBy: [{field: n}]\n$grouping")
        assertEquals(listOf("Аа" to listOf("a2", "a1"), "Бб" to listOf("b1")), names(out))
    }

    @Test
    fun `a value outside order and omit is an error at shaping`() {
        val e = assertFailsWith<IllegalStateException> {
            FlowShaper.shape(spec("groupBy: {field: kind, order: [A], titles: {A: a}}"), schema, sample)
        }
        assertTrue("B, C" in e.message!! || "C, B" in e.message!!, e.message)
    }

    // ---- runtime: computed ----

    private fun positions(groups: List<ShapedGroup>) = groups.map { g -> g.rows.map { (it.fields["pos"] as DataValue.Integer).value } }

    @Test
    fun `sequence scope table runs through all groups in table order`() {
        val out = shape("$grouping\ncomputed: {pos: {sequence: {scope: table}}}")
        assertEquals(listOf(listOf(1L), listOf(2L, 3L), listOf(4L)), positions(out))
        assertEquals(listOf("c1", "a1", "a2", "b1"), out.flatMap { g -> g.rows.map { (it.fields["name"] as DataValue.Str).value } })
    }

    @Test
    fun `sequence scope group restarts, start and step apply, flat table is one group`() {
        assertEquals(listOf(listOf(10L), listOf(10L, 15L), listOf(10L)), positions(shape("$grouping\ncomputed: {pos: {sequence: {scope: group, start: 10, step: 5}}}")))
        assertEquals(listOf(listOf(3L, 2L, 1L, 0L)), positions(shape("computed: {pos: {sequence: {start: 3, step: -1}}}")))
        val flat = shape("")
        assertEquals(listOf(null), flat.map { it.title })
        // two sequences side by side
        val two = shape("$grouping\ncomputed: {t: {sequence: {}}, g: {sequence: {scope: group}}}")
        assertEquals(listOf(1L, 1L, 2L, 1L), two.flatMap { g -> g.rows.map { r -> (r.fields["g"] as DataValue.Integer).value } })
        assertEquals(listOf(1L, 2L, 3L, 4L), two.flatMap { g -> g.rows.map { r -> (r.fields["t"] as DataValue.Integer).value } })
    }

    // ---- runtime: cells (cases, format) ----

    private fun cell(cellYaml: String, record: DataValue.Record, computedExtra: String = ""): String {
        val s = spec(computedExtra, "row: {cells: {a: $cellYaml}}")
        assertEquals(emptyMap(), contract(s), "contract")
        val data = MapDataContext(s.itemSchema(schema), mapOf("item" to record))
        return FlowCellRenderer(s.rowCells.getValue("a"), s.itemFields(schema)).render(record, data)
    }

    private val qtyCell = """
        {cases: [{where: {field: kind, eq: A}, bind: "${'$'}{item.qty}", format: {pattern: "0.##", locale: ru}}],
         bind: "${'$'}{item.qty}", format: {pattern: "0"}}
    """.trimIndent()

    @Test
    fun `first matching case wins, otherwise the cell's default, with format per variant`() {
        assertEquals("1,5", cell(qtyCell, rec("x", "A", "1.50")))
        assertEquals("2", cell(qtyCell, rec("x", "B", "1.5")))   // HALF_UP default
        assertEquals("1", cell(qtyCell, rec("x", "B", "1.49")))
        val ordered = "{cases: [{where: {field: kind, in: [A, B]}, text: one}, {where: {field: kind, eq: A}, text: two}], text: other}"
        assertEquals("one", cell(ordered, rec("x", "A")))
        assertEquals("one", cell(ordered, rec("x", "B")))
        assertEquals("other", cell(ordered, rec("x", "C")))
    }

    @Test
    fun `no matching case and no default content gives an empty cell`() {
        val c = "{cases: [{where: {field: kind, eq: A}, bind: \"\${item.name}\"}]}"
        assertEquals("x", cell(c, rec("x", "A")))
        assertEquals("", cell(c, rec("x", "B")))
    }

    @Test
    fun `optional is per variant, a missing required value fails`() {
        val c = "{cases: [{where: {field: kind, eq: A}, bind: \"\${item.note}\", optional: true}, {where: {field: kind, eq: B}, bind: \"\${item.note}\"}]}"
        assertEquals("", cell(c, rec("x", "A", note = null)))
        assertEquals("n", cell(c, rec("x", "B", note = "n")))
        assertFailsWith<IllegalStateException> { cell(c, rec("x", "B", note = null)) }
    }

    @Test
    fun `cases may read computed fields`() {
        val c = "{cases: [{where: {field: pos, eq: 1}, text: first}], bind: \"\${item.pos}\"}"
        val s = spec("computed: {pos: {sequence: {}}}", "row: {cells: {a: $c}}")
        val rows = FlowShaper.shape(s, schema, sample).single().rows
        val data = { r: DataValue.Record -> MapDataContext(s.itemSchema(schema), mapOf("item" to r)) }
        val r = FlowCellRenderer(s.rowCells.getValue("a"), s.itemFields(schema))
        assertEquals(listOf("first", "2", "3", "4"), rows.map { r.render(it, data(it)) })
    }

    // ---- declared enum values (data files) ----

    @Test
    fun `declaredEnumValues collects what the template names`() {
        val t = spec(
            "groupBy: {field: kind, order: [A, B], titles: {A: a, B: b}, omit: [C]}\nwhere: {field: tag, eq: X}",
            "row: {cells: {a: {cases: [{where: {field: tag, in: [Y, Z]}, text: t}]}}}"
        )
        assertEquals(mapOf("kind" to setOf("A", "B", "C"), "tag" to setOf("X", "Y", "Z")), t.declaredEnumValues())
    }

    @Test
    fun `data file fields named by groupBy are read as enums with the template's domain`() {
        val file = DataYaml.parseFile("item:\n  - {kind: A, qty: 1}\n  - {kind: B, qty: 2.5}\n  - {kind: Q, qty: 1}", mapOf("kind" to listOf("A", "B", "C")))
        assertEquals(DataType.Enum(listOf("A", "B", "C", "Q")), file.context.schema.typeOf("item.kind"))
        assertEquals(DataType.Decimal, file.context.schema.typeOf("item.qty"), "1 and 2.5 merge into Decimal")
        assertEquals(DataValue.Decimal(BigDecimal.ONE), file.items[0].fields["qty"])
        assertEquals(DataValue.Enum("A"), file.items[0].fields["kind"])
        // Q is not covered by the template: the contract says so instead of dropping the row
        val t = spec("groupBy: {field: kind, order: [A, B, C], titles: {A: a, B: b, C: c}}", "row: {cells: {a: x}}")
        val e = TemplateContract.checkFlowTable(t, file.context.schema, "t")
        assertEquals(listOf("t.groupBy.order"), e.map { it.path })
    }

    @Test
    fun `enum tag declares the domain from the values in the file`() {
        val file = DataYaml.parseFile("item:\n  - {kind: !enum A}\n  - {kind: !enum B}")
        assertEquals(DataType.Enum(listOf("A", "B")), file.context.schema.typeOf("item.kind"))
    }
}
