package dev.reportgenerator.template

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataTest {
    private val ctx = dataContext {
        doc {
            string("designation", "AB.001")
            integer("count", 3)
            decimal("mass", BigDecimal("1234.565"))
            date("issued", LocalDate.of(2026, 1, 5))
            bool("approved", true)
            enum("kind", listOf("PART", "ASSEMBLY"), "PART")
            string("note")
            record("customer") { string("name", "ACME") }
            list("items") {
                row { string("name", "a") }
                row { string("name", "b"); integer("qty", 2) }
            }
        }
    }

    @Test
    fun `schema lookup finds scalars, records and lists`() {
        val s = ctx.schema
        assertEquals(DataType.Str, s.typeOf("doc.designation"))
        assertEquals(DataType.Decimal, s.typeOf("doc.mass"))
        assertEquals(DataType.Str, s.typeOf("doc.customer.name"))
        assertIs<DataType.Record>(s.typeOf("doc.customer"))
        val items = assertIs<DataType.ListOf>(s.typeOf("doc.items"))
        assertEquals(setOf("name", "qty"), items.of.fields.keys)
        assertEquals(DataType.Enum(listOf("PART", "ASSEMBLY")), s.typeOf("doc.kind"))
    }

    @Test
    fun `page number and total are always declared`() {
        assertEquals(DataType.Integer, DataSchema().typeOf("page.number"))
        assertEquals(DataType.Integer, ctx.schema.typeOf("page.total"))
    }

    @Test
    fun `lookup reports unknown root, field and stepping into a scalar`() {
        assertIs<DataSchema.Lookup.UnknownRoot>(ctx.schema.lookup("zzz.a"))
        val unknown = assertIs<DataSchema.Lookup.UnknownField>(ctx.schema.lookup("doc.nope"))
        assertEquals("doc", unknown.parent)
        assertTrue("designation" in unknown.known)
        assertIs<DataSchema.Lookup.NotARecord>(ctx.schema.lookup("doc.designation.x"))
        assertIs<DataSchema.Lookup.NotARecord>(ctx.schema.lookup("doc.items.name"))
    }

    @Test
    fun `values are typed and nested paths resolve`() {
        assertEquals(DataValue.Str("AB.001"), ctx.get("doc.designation"))
        assertEquals(DataValue.Integer(3), ctx.get("doc.count"))
        assertEquals(DataValue.Decimal(BigDecimal("1234.565")), ctx.get("doc.mass"))
        assertEquals(DataValue.Date(LocalDate.of(2026, 1, 5)), ctx.get("doc.issued"))
        assertEquals(DataValue.Str("ACME"), ctx.get("doc.customer.name"))
        assertNull(ctx.get("doc.note"))
        assertNull(ctx.get("doc.designation.x"))
        assertEquals(2, assertIs<DataValue.ListOf>(ctx.get("doc.items")).items.size)
        assertTrue(DataValue.Enum("PART").conformsTo(DataType.Enum(listOf("PART"))))
        assertTrue(!DataValue.Enum("X").conformsTo(DataType.Enum(listOf("PART"))))
    }

    @Test
    fun `overlay supplies page values over the document data`() {
        val page = ctx.withPage(2, 5)
        assertEquals(DataValue.Integer(2), page.get("page.number"))
        assertEquals(DataValue.Integer(5), page.get("page.total"))
        assertEquals(DataValue.Str("AB.001"), page.get("doc.designation"))
        assertNull(ctx.get("page.number"))
        assertEquals(ctx.schema, page.schema)
    }

    @Test
    fun `default rendering per type`() {
        fun r(path: String) = ValueFormatter.render(checkNotNull(ctx.get(path)))
        assertEquals("AB.001", r("doc.designation"))
        assertEquals("3", r("doc.count"))
        assertEquals("1234.565", r("doc.mass"))
        assertEquals("2026-01-05", r("doc.issued"))
        assertEquals("true", r("doc.approved"))
        assertEquals("PART", r("doc.kind"))
    }

    @Test
    fun `decimal format honours pattern, locale and rounding`() {
        val v = DataValue.of(BigDecimal("1234.565"))
        assertEquals("1234.57", ValueFormatter.render(v, FormatSpec("0.##")))
        assertEquals("1234,57", ValueFormatter.render(v, FormatSpec("0.##", "ru")))
        assertEquals("1234.56", ValueFormatter.render(v, FormatSpec("0.##", rounding = RoundingMode.DOWN)))
        assertEquals("2.50", ValueFormatter.render(DataValue.of(BigDecimal("2.5")), FormatSpec("0.00")))
        assertEquals("3", ValueFormatter.render(DataValue.of(BigDecimal("2.5")), FormatSpec("0")))
        assertEquals("2", ValueFormatter.render(DataValue.of(BigDecimal("2.5")), FormatSpec("0", rounding = RoundingMode.HALF_EVEN)))
    }

    @Test
    fun `date format honours pattern`() {
        val v = DataValue.of(LocalDate.of(2026, 1, 5))
        assertEquals("05.01.2026", ValueFormatter.render(v, FormatSpec("dd.MM.yyyy")))
        assertEquals("5 января 2026", ValueFormatter.render(v, FormatSpec("d MMMM yyyy", "ru")))
    }

    @Test
    fun `format problems are reported per type`() {
        assertNull(ValueFormatter.problem(DataType.Decimal, FormatSpec("0.##")))
        assertTrue(ValueFormatter.problem(DataType.Decimal, FormatSpec())!!.contains("pattern"))
        assertTrue(ValueFormatter.problem(DataType.Decimal, FormatSpec("0.#.#"))!!.contains("invalid decimal pattern"))
        assertTrue(ValueFormatter.problem(DataType.Date, FormatSpec("qqqqqqqq"))!!.contains("invalid date pattern"))
        assertTrue(ValueFormatter.problem(DataType.Date, FormatSpec("yyyy", rounding = RoundingMode.UP))!!.contains("rounding"))
        assertTrue(ValueFormatter.problem(DataType.Str, FormatSpec("x"))!!.contains("not supported"))
        assertFailsWith<IllegalStateException> { ValueFormatter.render(DataValue.of("x"), FormatSpec("x")) }
    }

    @Test
    fun `binding render handles optional, missing and non scalar values`() {
        assertEquals("AB.001", Binding.render("\${doc.designation}", null, false, ctx))
        assertEquals("", Binding.render("\${doc.note}", null, true, ctx))
        assertFailsWith<IllegalStateException> { Binding.render("\${doc.note}", null, false, ctx) }
        assertFailsWith<IllegalStateException> { Binding.render("\${doc.items}", null, false, ctx) }
        assertEquals("2 / 5", Binding.render("\${page.number}", null, false, ctx.withPage(2, 5)) + " / " +
            Binding.render("\${page.total}", null, false, ctx.withPage(2, 5)))
    }

    @Test
    fun `data yaml infers types from shape and tags`() {
        val data = DataYaml.parse(
            """
            doc:
              designation: AB.001
              code: !str "007"
              count: 3
              mass: 1.50
              issued: 2026-01-05
              approved: true
              note: ~
              customer: {name: ACME}
              items:
                - {name: a, qty: 1}
                - {name: b}
            """.trimIndent()
        )
        val s = data.schema
        assertEquals(DataType.Str, s.typeOf("doc.designation"))
        assertEquals(DataType.Str, s.typeOf("doc.code"))
        assertEquals(DataType.Integer, s.typeOf("doc.count"))
        assertEquals(DataType.Decimal, s.typeOf("doc.mass"))
        assertEquals(DataType.Date, s.typeOf("doc.issued"))
        assertEquals(DataType.Bool, s.typeOf("doc.approved"))
        assertEquals(DataType.Str, s.typeOf("doc.note"))
        assertEquals(DataType.Str, s.typeOf("doc.customer.name"))
        assertIs<DataType.ListOf>(s.typeOf("doc.items"))
        assertEquals(DataValue.Str("007"), data.get("doc.code"))
        assertEquals(DataValue.Decimal(BigDecimal("1.50")), data.get("doc.mass"))
        assertNull(data.get("doc.note"))
    }

    @Test
    fun `data yaml errors carry a path`() {
        val badRoot = assertFailsWith<TemplateException> { DataYaml.parse("zzz: {a: 1}") }
        assertEquals("zzz", badRoot.errors.single().path)
        val badInt = assertFailsWith<TemplateException> { DataYaml.parse("doc: {n: !int abc}") }
        assertEquals("doc.n", badInt.errors.single().path)
        val scalarList = assertFailsWith<TemplateException> { DataYaml.parse("doc: {l: [1, 2]}") }
        assertEquals("doc.l[0]", scalarList.errors.single().path)
    }

    @Test
    fun `item root as a sequence gives the flow table rows and a row schema`() {
        val file = DataYaml.parseFile("doc: {a: x}\nitem:\n  - {n: 1, name: A}\n  - {n: 2, name: B, note: z}")

        assertEquals(2, file.items.size)
        assertEquals(DataValue.of(2), file.items[1].fields["n"])
        assertEquals(
            DataType.Record(mapOf("n" to DataType.Integer, "name" to DataType.Str, "note" to DataType.Str)),
            file.context.schema.roots["item"]
        )
        assertEquals(DataType.Integer, file.context.schema.typeOf("item.n"))
        // `item` as a plain mapping still works and yields no rows
        assertEquals(0, DataYaml.parseFile("item: {n: 1}").items.size)
    }

    @Test
    fun `mixed types in one item field are an error naming both rows, widening and tags are fine`() {
        val e = assertFailsWith<TemplateException> {
            DataYaml.parseFile("item:\n  - {n: 1, status: 5}\n  - {n: 2, status: 7}\n  - {n: 3, status: ok}")
        }
        val error = e.errors.single()
        assertEquals("item[2].status", error.path)
        assertTrue("String here, Integer in item[0].status (row 3 and row 1)" in error.message, error.message)

        // 1 and 1.5 widen to Decimal; a tag makes every row one type; a `~` row takes no part
        val widened = DataYaml.parseFile("item:\n  - {q: 1}\n  - {q: 1.5}")
        assertEquals(DataType.Decimal, widened.context.schema.typeOf("item.q"))
        val tagged = DataYaml.parseFile("item:\n  - {s: !str 5}\n  - {s: ok}")
        assertEquals(DataType.Str, tagged.context.schema.typeOf("item.s"))
        val empty = DataYaml.parseFile("item:\n  - {s: ~}\n  - {s: 5}")
        assertEquals(DataType.Integer, empty.context.schema.typeOf("item.s"))
        // the same rule inside a nested list of `doc`
        assertFailsWith<TemplateException> { DataYaml.parseFile("doc:\n  rows:\n    - {a: 1}\n    - {a: x}") }
    }
}
