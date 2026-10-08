package dev.reportgenerator.template

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateContractTest {
    private val schema = dataSchema {
        doc {
            string("designation")
            string("name")
            decimal("mass", BigDecimal.ONE)
            date("issued", LocalDate.of(2026, 1, 1))
            bool("ok")
            record("customer") { string("name") }
            list("items") { row { string("name") } }
        }
    }

    private fun check(yaml: String): List<TemplateError> = TemplateContract.check(TemplateLoader.load(yaml), schema)

    private fun errors(yaml: String): Map<String, String> = check(yaml).associate { it.path to it.message }

    private fun textBlock(attrs: String) =
        "blocks:\n  - {id: t, type: text, size: {width: 10, height: 5}, $attrs}"

    @Test
    fun `valid binds pass, including page values`() {
        val yaml = """
            blocks:
              - id: t
                type: table
                columns: [10, 10]
                rows:
                  - height: 5
                    cells:
                      - {bind: "${'$'}{doc.designation}"}
                      - {bind: "${'$'}{page.number}"}
                  - height: 5
                    cells:
                      - {bind: "${'$'}{doc.customer.name}"}
                      - {bind: "${'$'}{page.total}"}
              - {id: x, type: text, size: {width: 10, height: 5}, bind: "${'$'}{doc.mass}", format: {pattern: "0.00", locale: ru}}
        """.trimIndent()
        assertEquals(emptyList(), check(yaml))
    }

    @Test
    fun `unknown field gives YAML path and a suggestion`() {
        val e = errors(textBlock("""bind: "${'$'}{doc.desigantion}""""))
        val msg = e.getValue("blocks[0].bind")
        assertTrue("unknown field 'doc.desigantion'" in msg, msg)
        assertTrue("did you mean 'doc.designation'?" in msg, msg)
    }

    @Test
    fun `unknown field far from every name has no suggestion`() {
        val msg = errors(textBlock("""bind: "${'$'}{doc.zzzzzzzz}"""")).getValue("blocks[0].bind")
        assertTrue("did you mean" !in msg, msg)
        assertTrue("doc has: designation" in msg, msg)
    }

    @Test
    fun `unknown item field and path through a scalar are errors`() {
        assertTrue("blocks[0].bind" in errors(textBlock("""bind: "${'$'}{item.name}"""")))
        val msg = errors(textBlock("""bind: "${'$'}{doc.designation.x}"""")).getValue("blocks[0].bind")
        assertTrue("not a record" in msg, msg)
    }

    @Test
    fun `record and list are not scalar`() {
        assertTrue("not a scalar" in errors(textBlock("""bind: "${'$'}{doc.customer}"""")).getValue("blocks[0].bind"))
        assertTrue("List" in errors(textBlock("""bind: "${'$'}{doc.items}"""")).getValue("blocks[0].bind"))
    }

    @Test
    fun `format must fit the type`() {
        val onString = errors(textBlock("""bind: "${'$'}{doc.name}", format: {pattern: "0"}"""))
        assertTrue("not supported for String" in onString.getValue("blocks[0].format"))
        val noPattern = errors(textBlock("""bind: "${'$'}{doc.mass}", format: {locale: ru}"""))
        assertTrue("needs 'pattern'" in noPattern.getValue("blocks[0].format"))
        val badPattern = errors(textBlock("""bind: "${'$'}{doc.issued}", format: {pattern: "qqqqqqqq"}"""))
        assertTrue("invalid date pattern" in badPattern.getValue("blocks[0].format"))
        val dateRounding = errors(textBlock("""bind: "${'$'}{doc.issued}", format: {pattern: "yyyy", rounding: UP}"""))
        assertTrue("rounding" in dateRounding.getValue("blocks[0].format"))
    }

    @Test
    fun `loader rejects unknown format keys, locales and roundings`() {
        fun fails(attrs: String) = assertFailsWith<TemplateException> { TemplateLoader.load(textBlock(attrs)) }.errors.single().path
        assertEquals("blocks[0].format.foo", fails("""bind: "${'$'}{doc.mass}", format: {pattern: "0", foo: 1}"""))
        assertEquals("blocks[0].format.locale", fails("""bind: "${'$'}{doc.mass}", format: {pattern: "0", locale: xx}"""))
        assertEquals("blocks[0].format.rounding", fails("""bind: "${'$'}{doc.mass}", format: {pattern: "0", rounding: SIDEWAYS}"""))
    }

    @Test
    fun `format and optional need bind`() {
        val a = assertFailsWith<TemplateException> {
            TemplateLoader.load("blocks:\n  - {id: t, type: text, text: x, size: {width: 10, height: 5}, optional: true}")
        }
        assertEquals("blocks[0].optional", a.errors.single().path)
        val b = assertFailsWith<TemplateException> {
            TemplateLoader.load("blocks:\n  - {id: t, type: text, text: x, size: {width: 10, height: 5}, format: {pattern: \"0\"}}")
        }
        assertEquals("blocks[0].format", b.errors.single().path)
    }

    @Test
    fun `optional and format are kept on cells and in the resolved model`() {
        val yaml = """
            blocks:
              - id: t
                type: table
                columns: [10]
                rows:
                  - {height: 5, cells: [{bind: "${'$'}{doc.mass}", optional: true, format: {pattern: "0.0", rounding: DOWN}}]}
        """.trimIndent()
        val cell = TemplateResolver.resolve(TemplateLoader.load(yaml), PageKind.FIRST).block("t")!!.cells.single()
        assertTrue(cell.optional)
        assertEquals("0.0", cell.format?.pattern)
        assertEquals(java.math.RoundingMode.DOWN, cell.format?.rounding)
    }

    @Test
    fun `table cell paths cover rows and repeat rows`() {
        val yaml = """
            blocks:
              - id: t
                type: table
                columns: [10]
                rows:
                  - {height: 5, cells: ["x"]}
                  - {height: 5, cells: [{bind: "${'$'}{doc.nope1}"}]}
                  - repeat: {count: 2, row: {height: 5, cells: [{bind: "${'$'}{doc.nope2}"}]}}
        """.trimIndent()
        val paths = check(yaml).map { it.path }
        assertEquals(listOf("blocks[0].rows[1].cells[0].bind", "blocks[0].rows[2].repeat.row.cells[0].bind"), paths)
    }

    @Test
    fun `blockset definition binds are checked at their definition path`() {
        val yaml = """
            blocksets:
              sig:
                params: {w: 10}
                blocks:
                  - {id: a, type: rect, size: {width: "${'$'}{param.w}", height: 5}}
                  - {id: b, type: text, size: {width: "${'$'}{param.w}", height: 5}, bind: "${'$'}{doc.bogus}"}
            blocks:
              - {id: one, use: sig}
              - {id: two, use: sig}
        """.trimIndent()
        val errs = check(yaml)
        assertEquals(listOf("blocksets.sig.blocks[1].bind"), errs.map { it.path })
    }

    @Test
    fun `all errors are collected and require throws`() {
        val yaml = """
            blocks:
              - {id: a, type: text, size: {width: 10, height: 5}, bind: "${'$'}{doc.x1}"}
              - {id: b, type: text, size: {width: 10, height: 5}, bind: "${'$'}{doc.x2}"}
        """.trimIndent()
        assertEquals(2, check(yaml).size)
        assertEquals(2, assertFailsWith<TemplateException> { TemplateContract.require(TemplateLoader.load(yaml), schema) }.errors.size)
    }
}
