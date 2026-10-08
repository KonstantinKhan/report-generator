package dev.reportgenerator.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Multi-level flow table header (`header.rows`, `span` / `rowSpan`): loader (shape, exclusivity), validator (the grid
// coverage rules and messages of a `table` block, with YAML paths) and the placement of the cells over the columns.
class MultiHeaderTest {
    private fun yaml(header: String, columns: Int = 4) =
        "sheet: {format: A4, margins: {top: 5, right: 5, bottom: 5, left: 20}}\nblocks:\n  - id: body\n    type: flow\n    table:\n" +
            ("rowHeight: 8\ncolumns:\n" + (0 until columns).joinToString("\n") { "  - {id: c$it, width: ${185 / columns}}" }.let { it } + "\n" +
                header + "\nrow:\n  cells:\n" + (0 until columns).joinToString("\n") { "    c$it: ~" }).prependIndent("      ")

    // widths must add up to the 185 mm flow region: 4 columns of 46.25 do, 5 of 37 do, 3 do not divide -> use 1 / 5
    private fun table(header: String, columns: Int = 5) = (TemplateLoader.parse(yaml(header, columns)).blocks.single() as FlowBlock).table!!

    private fun errors(header: String, columns: Int = 5): Map<String, String> =
        TemplateValidator.validate(TemplateLoader.parse(yaml(header, columns)), null).associate { it.path to it.message }

    private val ok = """
        header:
          repeat: true
          rows:
            - height: 9
              cells:
                - {text: "А", rowSpan: 2, rotate: 90, style: tableHeader}
                - {text: "Б", rowSpan: 2}
                - {text: "Количество", span: 3}
            - height: 18
              cells:
                - {text: "x", lines: ["x"]}
                - {text: "y"}
                - {text: "z", align: left}
    """.trimIndent()

    private val p = "blocks[0].table.header"

    @Test
    fun `rows form loads into the model, height is the sum of the rows`() {
        val h = table(ok).header!!
        assertEquals(27.0, h.height)
        assertEquals(emptyMap(), h.cells)
        assertEquals(listOf(9.0, 18.0), h.rows.map { it.height })
        assertEquals(FlowHeaderCell("А", rotate = 90, style = "tableHeader", rowSpan = 2), h.rows[0].cells[0])
        assertEquals(FlowHeaderCell("Количество", span = 3), h.rows[0].cells[2])
        assertEquals(FlowHeaderCell("z", align = TextAlign.LEFT), h.rows[1].cells[2])
        assertEquals(listOf("x"), h.rows[1].cells[0].lines)
        assertEquals(emptyList(), TemplateValidator.validate(TemplateLoader.parse(yaml(ok, 5)), null))
    }

    @Test
    fun `the single-row form is unchanged`() {
        val h = table("header: {height: 10, cells: {c0: a, c1: b, c2: c, c3: d, c4: e}}").header!!
        assertEquals(10.0, h.height)
        assertEquals(emptyList(), h.rows)
        assertEquals(FlowHeaderCell("a"), h.cells["c0"])
        assertEquals(1, h.cells["c0"]!!.span)
        assertEquals(1, h.cells["c0"]!!.rowSpan)
    }

    @Test
    fun `cells are placed over the columns by order, columns covered from above are skipped`() {
        val placed = table(ok).header!!.placeCells(5)
        assertEquals(
            listOf(Triple(0, 0, "А"), Triple(0, 1, "Б"), Triple(0, 2, "Количество"), Triple(1, 2, "x"), Triple(1, 3, "y"), Triple(1, 4, "z")),
            placed.map { Triple(it.row, it.col, it.cell.text) }
        )
    }

    @Test
    fun `rows and height or cells are mutually exclusive, one of them is required`() {
        val both = assertFailsWith<TemplateException> {
            TemplateLoader.parse(yaml("header: {height: 5, rows: [{height: 5, cells: [{text: a, span: 5}]}]}", 5))
        }.errors.single()
        assertEquals(p, both.path)
        assertTrue("'rows' excludes 'height'" in both.message, both.message)

        val both2 = assertFailsWith<TemplateException> {
            TemplateLoader.parse(yaml("header: {cells: {c0: a}, rows: []}", 5))
        }.errors.single()
        assertTrue("'rows' excludes 'cells'" in both2.message, both2.message)

        val none = assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("header: {repeat: true}", 5)) }.errors.single()
        assertEquals(p, none.path)
        assertTrue("expected 'rows' or 'height' + 'cells'" in none.message, none.message)

        // a model built in code with both is rejected by the validator too
        val flow = TemplateLoader.parse(yaml(ok, 5))
        val t = (flow.blocks.single() as FlowBlock).table!!
        val bad = flow.copy(blocks = listOf((flow.blocks.single() as FlowBlock).copy(table = t.copy(header = t.header!!.copy(cells = mapOf("c0" to FlowHeaderCell("a")))))))
        val e = TemplateValidator.validate(bad, null).associate { it.path to it.message }
        assertTrue("'rows' excludes" in e.getValue(p), e.toString())
    }

    @Test
    fun `rows form cells are objects and span rowSpan are not allowed in the map form`() {
        val scalar = assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("header: {rows: [{height: 5, cells: [abc]}]}", 5)) }.errors.single()
        assertEquals("$p.rows[0].cells[0]", scalar.path)

        val text = assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("header: {rows: [{height: 5, cells: [{span: 5}]}]}", 5)) }.errors.single()
        assertEquals("$p.rows[0].cells[0]", text.path)
        assertTrue("'text'" in text.message)

        val map = assertFailsWith<TemplateException> {
            TemplateLoader.parse(yaml("header: {height: 5, cells: {c0: {text: a, span: 2}}}", 5))
        }.errors.single()
        assertEquals("$p.cells.c0.span", map.path)

        val rowKey = assertFailsWith<TemplateException> { TemplateLoader.parse(yaml("header: {rows: [{height: 5, cells: [], bogus: 1}]}", 5)) }.errors.single()
        assertEquals("$p.rows[0].bogus", rowKey.path)

        assertEquals("$p.rows[0].cells[0].rowSpan", assertFailsWith<TemplateException> {
            TemplateLoader.parse(yaml("header: {rows: [{height: 5, cells: [{text: a, rowSpan: two}]}]}", 5))
        }.errors.single().path)
    }

    @Test
    fun `a row that does not fill the columns is reported at its cells`() {
        // row 0: 2 + 1 = 3 of 5 columns
        val gap = errors("header: {rows: [{height: 5, cells: [{text: a, span: 2}, {text: b}]}]}")
        assertEquals("cell spans add up to 3, table has 5 columns", gap["$p.rows[0].cells"])
        // span past the last column
        val past = errors("header: {rows: [{height: 5, cells: [{text: a, span: 6}]}]}")
        assertEquals("cell spans add up to 6, table has 5 columns", past["$p.rows[0].cells"])
    }

    @Test
    fun `a cell overlapping a rowSpan from above and a row that is too full are reported`() {
        val overlap = errors(
            """
            header:
              rows:
                - height: 5
                  cells: [{text: a, rowSpan: 2}, {text: b, span: 4}]
                - height: 5
                  cells: [{text: c, span: 2}, {text: d, span: 2}, {text: e}]
            """.trimIndent()
        )
        // row 1 has column 0 taken from above: 1 + 2 + 2 + 1 = 6 > 5
        assertEquals("cell spans add up to 6, table has 5 columns", overlap["$p.rows[1].cells"])
        assertEquals(setOf("$p.rows[1].cells"), overlap.keys)

        val under = errors(
            """
            header:
              rows:
                - height: 5
                  cells: [{text: a}, {text: b, rowSpan: 2}, {text: c, span: 3}]
                - height: 5
                  cells: [{text: x, span: 2}, {text: y}, {text: z}]
            """.trimIndent()
        )
        // row 1: column 1 is taken from above, x (span 2) at column 0 would run into it; 1 + 2 + 1 + 1 = 5 columns in total
        assertEquals("cell 0 overlaps a cell spanning down from a row above", under["$p.rows[1].cells"])
    }

    @Test
    fun `rowSpan beyond the last header row`() {
        val e = errors("header: {rows: [{height: 5, cells: [{text: a, rowSpan: 2, span: 5}]}]}")
        assertEquals("cell 0 rowSpan 2 exceeds the table's 1 rows", e["$p.rows[0].cells"])
    }

    @Test
    fun `numbers and fields of the cells are checked with paths`() {
        val e = errors(
            """
            header:
              rows:
                - height: 0
                  cells: [{text: a, span: 0, lines: [], style: nope}, {text: b, rowSpan: 0, span: 4, rotate: 90, lines: [x]}]
            """.trimIndent()
        )
        assertEquals("must be > 0, got 0.0", e["$p.rows[0].height"])
        assertEquals("must be >= 1", e["$p.rows[0].cells[0].span"])
        assertEquals("must be >= 1", e["$p.rows[0].cells[1].rowSpan"])
        assertEquals("must not be empty", e["$p.rows[0].cells[0].lines"])
        assertTrue("manual break" in e.getValue("$p.rows[0].cells[1].lines"))
        // with a bad span the grid is not placed (no extra coverage errors)
        assertTrue(e.keys.none { it.endsWith(".cells") }, e.toString())
        val styles = TemplateValidator.validate(
            TemplateLoader.parse(yaml("header: {rows: [{height: 5, cells: [{text: a, span: 5, style: nope}]}]}", 5)), setOf("tableHeader")
        ).associate { it.path to it.message }
        assertTrue("unknown style 'nope'" in styles.getValue("$p.rows[0].cells[0].style"), styles.toString())
    }
}
