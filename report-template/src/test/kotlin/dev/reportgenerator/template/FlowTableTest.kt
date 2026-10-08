package dev.reportgenerator.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// `type: flow` with a `table:` section: loader (shape, unknown keys), validator (structure, widths, styles)
// and contract (row binds against the `item` schema). Every error carries a YAML path.
class FlowTableTest {
    private fun yaml(table: String, sheet: String = "sheet: {format: A4, margins: {top: 5, right: 5, bottom: 5, left: 20}}", flowAttrs: String = "") =
        "$sheet\nblocks:\n  - id: body\n    type: flow\n$flowAttrs    table:\n" + table.trimIndent().prependIndent("      ")

    private val ok = """
        rowHeight: 8
        fill: blank
        keep: {titleChain: false}
        styles: {head: tableHeader, data: tableText}
        columns:
          - {id: pos, width: 15, stick: first, align: center}
          - {id: name, width: 170}
        header:
          height: 15
          repeat: false
          cells:
            pos: {text: "Поз.", rotate: 90, style: head}
            name: {text: "Наименование", lines: ["Наиме-", "нование"]}
        groupBy: {field: kind, order: [A, B], titles: {A: "Группа А", B: "Группа Б"}}
        groupTitle: {column: name, style: head, align: center, spacerBefore: 2, spacerAfter: 1}
        row:
          cells:
            pos: {bind: "${'$'}{item.position}", style: data}
            name: {bind: "${'$'}{item.name}", optional: true, align: center}
    """

    private fun errors(table: String, styles: Set<String>? = null, sheet: String? = null): Map<String, String> {
        val text = if (sheet != null) yaml(table, sheet) else yaml(table)
        return TemplateValidator.validate(TemplateLoader.parse(text), styles).associate { it.path to it.message }
    }

    private fun parseError(table: String): TemplateError =
        assertFailsWith<TemplateException> { TemplateLoader.parse(yaml(table)) }.errors.single()

    private val itemSchema = dataSchema {
        item {
            integer("position")
            string("name")
            enum("kind", listOf("A", "B"))
        }
    }

    @Test
    fun `table section loads into the model`() {
        val flow = TemplateLoader.load(yaml(ok), setOf("tableHeader", "tableText")).blocks.single() as FlowBlock
        val t = assertNotNull(flow.table)

        assertEquals(8.0, t.rowHeight)
        assertEquals(FlowFill.BLANK, t.fill)
        assertEquals(FlowKeep(titleChain = false), t.keep)
        assertEquals(listOf(FlowColumn("pos", 15.0, FlowStick.FIRST, TextAlign.CENTER), FlowColumn("name", 170.0)), t.columns)
        assertEquals(false, t.header?.repeat)
        assertEquals(FlowHeaderCell("Поз.", rotate = 90, style = "head"), t.header?.cells?.get("pos"))
        assertEquals(listOf("Наиме-", "нование"), t.header?.cells?.get("name")?.lines)
        assertEquals(FlowGroupTitle("name", "head", TextAlign.CENTER, 2, 1), t.groupTitle)
        assertEquals(FlowGroupBy("kind", listOf("A", "B"), mapOf("A" to "Группа А", "B" to "Группа Б")), t.groupBy)
        assertEquals(FlowRowCell(bind = "\${item.name}", optional = true, align = TextAlign.CENTER), t.rowCells["name"])
        assertEquals(mapOf("head" to FlowStyle("tableHeader"), "data" to FlowStyle("tableText")), t.styles)
    }

    @Test
    fun `defaults are repeat true, fill none, keep titleChain, no spacers`() {
        val t = (TemplateLoader.load(
            yaml(
                """
                rowHeight: 8
                columns: [{id: a, width: 185}]
                header: {height: 10, cells: {a: ~}}
                groupBy: {field: k, order: [X], titles: {X: T}}
                groupTitle: {column: a}
                row: {cells: {a: "const"}}
                """
            )
        ).blocks.single() as FlowBlock).table!!

        assertEquals(true, t.header?.repeat)
        assertEquals(FlowFill.NONE, t.fill)
        assertEquals(FlowKeep(), t.keep)
        assertEquals(FlowGroupTitle("a", null, TextAlign.CENTER, 0, 0), t.groupTitle)
        assertEquals(FlowHeaderCell(""), t.header?.cells?.get("a"))
        assertEquals(FlowRowCell(text = "const"), t.rowCells["a"])
    }

    @Test
    fun `remainder loads as stretch or gap, absent is null and the stretch default applies`() {
        fun remainder(extra: String) = (TemplateLoader.load(yaml(ok.replace("fill: blank", "fill: blank$extra")), setOf("tableHeader", "tableText")).blocks.single() as FlowBlock).table!!.remainder

        assertEquals(null, remainder(""))
        assertEquals(FlowRemainders(FlowRemainder.GAP, FlowRemainder.GAP), remainder("\n        remainder: gap"))
        assertEquals(FlowRemainders(FlowRemainder.STRETCH, FlowRemainder.STRETCH), remainder("\n        remainder: Stretch"))
    }

    @Test
    fun `remainder object sets first and rest separately, a missing key is stretch`() {
        fun remainder(extra: String) = (TemplateLoader.load(yaml(ok.replace("fill: blank", "fill: blank$extra")), setOf("tableHeader", "tableText")).blocks.single() as FlowBlock).table!!.remainder

        assertEquals(FlowRemainders(FlowRemainder.GAP, FlowRemainder.STRETCH), remainder("\n        remainder: {first: gap, rest: stretch}"))
        assertEquals(FlowRemainders(FlowRemainder.STRETCH, FlowRemainder.GAP), remainder("\n        remainder: {first: stretch, rest: gap}"))
        assertEquals(FlowRemainders(FlowRemainder.GAP, FlowRemainder.STRETCH), remainder("\n        remainder: {first: gap}"))
        assertEquals(FlowRemainders(FlowRemainder.STRETCH, FlowRemainder.GAP), remainder("\n        remainder: {rest: GAP}"))
        assertEquals(FlowRemainders(), remainder("\n        remainder: {}"))
    }

    @Test
    fun `remainder object rejects an unknown key or value with a path, and needs fill blank`() {
        val blank = "fill: blank\n        remainder: "
        assertEquals("blocks[0].table.remainder.middle", parseError(ok.replace("fill: blank", blank + "{middle: gap}")).path)
        assertEquals("blocks[0].table.remainder.first", parseError(ok.replace("fill: blank", blank + "{first: spread}")).path)
        assertEquals("blocks[0].table.remainder.rest", parseError(ok.replace("fill: blank", blank + "{rest: 1x}")).path)
        assertEquals(
            "'remainder' needs 'fill: blank'",
            errors(ok.replace("fill: blank", "fill: none\n        remainder: {first: gap}")).getValue("blocks[0].table.remainder")
        )
        assertEquals(emptyMap(), errors(ok.replace("fill: blank", blank + "{first: gap, rest: stretch}"), styles = setOf("tableHeader", "tableText")))
    }

    @Test
    fun `remainder rejects an unknown value and needs fill blank`() {
        assertEquals("blocks[0].table.remainder", parseError(ok.replace("fill: blank", "fill: blank\n        remainder: spread")).path)
        assertEquals(
            "'remainder' needs 'fill: blank'",
            errors(ok.replace("fill: blank", "remainder: gap")).getValue("blocks[0].table.remainder")
        )
        assertEquals(
            "'remainder' needs 'fill: blank'",
            errors(ok.replace("fill: blank", "fill: none\n        remainder: stretch")).getValue("blocks[0].table.remainder")
        )
        assertEquals(emptyMap(), errors(ok.replace("fill: blank", "fill: blank\n        remainder: gap"), styles = setOf("tableHeader", "tableText")))
    }

    @Test
    fun `unknown keys are rejected with a path`() {
        assertEquals("blocks[0].table.bogus", parseError("rowHeight: 8\nbogus: 1\ncolumns: []\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.columns[0].bogus", parseError("rowHeight: 8\ncolumns: [{id: a, width: 5, bogus: 1}]\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.header.bogus", parseError("rowHeight: 8\ncolumns: []\nheader: {height: 1, bogus: 1, cells: {}}\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.header.cells.a.bogus", parseError("rowHeight: 8\ncolumns: []\nheader: {height: 1, cells: {a: {text: x, bogus: 1}}}\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.groupTitle.bogus", parseError("rowHeight: 8\ncolumns: []\ngroupTitle: {column: a, bogus: 1}\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.row.cells.a.bogus", parseError("rowHeight: 8\ncolumns: []\nrow: {cells: {a: {text: x, bogus: 1}}}").path)
        assertEquals("blocks[0].table.row.bogus", parseError("rowHeight: 8\ncolumns: []\nrow: {cells: {}, bogus: 1}").path)
        assertEquals("blocks[0].table.keep.bogus", parseError("rowHeight: 8\ncolumns: []\nkeep: {bogus: 1}\nrow: {cells: {}}").path)
    }

    @Test
    fun `bad enum values and types are rejected with a path`() {
        assertEquals("blocks[0].table.columns[0].stick", parseError("rowHeight: 8\ncolumns: [{id: a, width: 5, stick: middle}]\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.columns[0].align", parseError("rowHeight: 8\ncolumns: [{id: a, width: 5, align: right}]\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.fill", parseError("rowHeight: 8\nfill: all\ncolumns: []\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.rowHeight", parseError("rowHeight: tall\ncolumns: []\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.header.repeat", parseError("rowHeight: 8\ncolumns: []\nheader: {height: 1, repeat: sometimes, cells: {}}\nrow: {cells: {}}").path)
        assertEquals("blocks[0].table.header.cells.a", parseError("rowHeight: 8\ncolumns: []\nheader: {height: 1, cells: {a: {rotate: 90}}}\nrow: {cells: {}}").path)
    }

    @Test
    fun `valid table has no errors`() {
        assertEquals(emptyMap(), errors(ok, styles = setOf("tableHeader", "tableText")))
    }

    @Test
    fun `column widths must add up to the flow region width`() {
        val short = ok.replace("width: 170", "width: 160")
        val e = errors(short)
        assertEquals(setOf("blocks[0].table.columns"), e.keys)
        assertTrue("sum to 175.0 mm" in e.getValue("blocks[0].table.columns") && "185.0 mm" in e.getValue("blocks[0].table.columns"), e.toString())
        // a hundredth of a millimetre is already a mismatch (no tolerance)
        assertEquals(setOf("blocks[0].table.columns"), errors(ok.replace("width: 170", "width: 170.01")).keys)
        // the width is the sheet content width: the same columns fit a 190 mm content area only with 190 mm of columns
        assertEquals(emptyMap(), errors(ok.replace("width: 170", "width: 175"), sheet = "sheet: {format: A4, margins: {left: 10, right: 10}}"))
    }

    @Test
    fun `structure errors carry yaml paths`() {
        val bad = """
            rowHeight: 0
            columns:
              - {id: a, width: 100}
              - {id: a, width: 85}
              - {id: "bad id", width: 0}
            header:
              height: -1
              cells:
                a: {text: x, rotate: 270}
                ghost: ~
            groupTitle: {column: nope, spacerBefore: -1, spacerAfter: -2}
            row:
              cells:
                a: {text: x, bind: "${'$'}{item.name}"}
                b: {bind: "${'$'}{doc.name}"}
                c: {format: {pattern: "0"}}
        """
        val e = errors(bad)
        assertEquals("must be > 0, got 0.0", e["blocks[0].table.rowHeight"])
        assertTrue("duplicate column id 'a'" in e.getValue("blocks[0].table.columns[1].id"))
        assertTrue("invalid id" in e.getValue("blocks[0].table.columns[2].id"))
        assertTrue(e.containsKey("blocks[0].table.columns[2].width"))
        assertTrue(e.containsKey("blocks[0].table.header.height"))
        assertTrue("0|90" in e.getValue("blocks[0].table.header.cells.a.rotate"))
        assertTrue("unknown column 'ghost'" in e.getValue("blocks[0].table.header.cells.ghost"))
        assertTrue("unknown column 'b'" in e.getValue("blocks[0].table.row.cells.b"))
        assertTrue("unknown column 'nope'" in e.getValue("blocks[0].table.groupTitle.column"))
        assertTrue(e.containsKey("blocks[0].table.groupTitle.spacerBefore"))
        assertTrue(e.containsKey("blocks[0].table.groupTitle.spacerAfter"))
        assertTrue("both 'text' and 'bind'" in e.getValue("blocks[0].table.row.cells.a"))
        assertTrue("expected \${item." in e.getValue("blocks[0].table.row.cells.b.bind"))
        assertTrue("'format' needs 'bind'" in e.getValue("blocks[0].table.row.cells.c.format"))
    }

    @Test
    fun `header and row must cover every column`() {
        val e = errors(
            """
            rowHeight: 8
            columns: [{id: a, width: 100}, {id: b, width: 85}]
            header: {height: 5, cells: {a: x}}
            row: {cells: {b: ~}}
            """
        )
        assertEquals("missing cell for column 'b'", e["blocks[0].table.header.cells"])
        assertEquals("missing cell for column 'a'", e["blocks[0].table.row.cells"])
    }

    @Test
    fun `header manual lines cannot be combined with rotation`() {
        val e = errors(ok.replace("""name: {text: "Наименование", lines: ["Наиме-", "нование"]}""", """name: {text: "Наименование", rotate: 90, lines: ["Наиме-", "нование"]}"""))
        assertTrue("manual break" in e.getValue("blocks[0].table.header.cells.name.lines"))
    }

    @Test
    fun `style references are checked against the known names`() {
        val e = errors(ok.replace("style: data", "style: nope"), styles = setOf("tableHeader", "tableText"))
        assertTrue("unknown style 'nope'" in e.getValue("blocks[0].table.row.cells.pos.style"))

        val alias = errors(ok.replace("data: tableText", "data: tableTxt"), styles = setOf("tableHeader", "tableText"))
        assertTrue("unknown style 'tableTxt'" in alias.getValue("blocks[0].table.styles.data"))

        val shadow = errors(ok.replace("data: tableText", "tableText: tableHeader"), styles = setOf("tableHeader", "tableText"))
        assertTrue("shadows a built-in style" in shadow.getValue("blocks[0].table.styles.tableText"))
        // without a known set the keys stay opaque
        assertEquals(emptyMap(), errors(ok.replace("style: data", "style: nope")))
    }

    private val known = setOf("tableHeader", "tableText")

    @Test
    fun `style object form is parsed, unspecified fields stay null (inherit)`() {
        val t = (TemplateLoader.parse(
            yaml(ok.replace("styles: {head: tableHeader, data: tableText}",
                "styles: {head: {base: tableHeader, size: 4.5, bold: true}, data: tableText, tiny: {base: tableText, size: 2, italic: false, underline: true}}"))
        ).blocks.single() as FlowBlock).table!!
        assertEquals(FlowStyle("tableHeader", size = 4.5, bold = true, asObject = true), t.styles["head"])
        assertEquals(FlowStyle("tableText"), t.styles["data"])
        assertEquals(FlowStyle("tableText", size = 2.0, italic = false, underline = true, asObject = true), t.styles["tiny"])
    }

    @Test
    fun `style object form errors carry the path of the field`() {
        fun withStyles(styles: String) = ok.replace("styles: {head: tableHeader, data: tableText}", "styles: $styles")
        val key = parseError(withStyles("{head: {base: tableHeader, sizes: 4}, data: tableText}"))
        assertEquals("blocks[0].table.styles.head.sizes", key.path)
        assertTrue("unknown field 'sizes'" in key.message)

        val noBase = parseError(withStyles("{head: {size: 4}, data: tableText}"))
        assertEquals("blocks[0].table.styles.head", noBase.path)
        assertTrue("missing required field 'base'" in noBase.message)

        val size = parseError(withStyles("{head: {base: tableHeader, size: big}, data: tableText}"))
        assertEquals("blocks[0].table.styles.head.size", size.path)

        val bold = parseError(withStyles("{head: {base: tableHeader, bold: yes}, data: tableText}"))
        assertEquals("blocks[0].table.styles.head.bold", bold.path)
        assertTrue("true|false" in bold.message)
    }

    @Test
    fun `style object form is validated, base against the known names, size above zero`() {
        fun withStyles(styles: String) = errors(ok.replace("styles: {head: tableHeader, data: tableText}", "styles: $styles"), styles = known)
        assertEquals(emptyMap(), withStyles("{head: {base: tableHeader, size: 4.5}, data: tableText}"))

        val base = withStyles("{head: {base: nope, size: 4}, data: tableText}")
        assertTrue("unknown style 'nope'" in base.getValue("blocks[0].table.styles.head.base"))

        assertTrue("must be > 0, got 0.0" in withStyles("{head: {base: tableHeader, size: 0}, data: tableText}").getValue("blocks[0].table.styles.head.size"))
        assertTrue("must be > 0, got -3.0" in withStyles("{head: {base: tableHeader, size: -3}, data: tableText}").getValue("blocks[0].table.styles.head.size"))

        // an alias never takes the name of a built-in style, with an object value either (also: same base)
        val shadow = withStyles("{head: tableHeader, data: tableText, tableText: {base: tableText, size: 5}}")
        assertTrue("shadows a built-in style" in shadow.getValue("blocks[0].table.styles.tableText"))

        // a style name of a cell resolves through an object alias
        assertEquals(emptyMap(), withStyles("{head: {base: tableHeader, size: 4.5}, data: tableText}"))
    }

    @Test
    fun `flow table fills the region, so size, when and blockset placement are rejected`() {
        val withSize = TemplateValidator.validate(TemplateLoader.parse(yaml(ok, flowAttrs = "    size: {width: 185, height: 100}\n"))).associate { it.path to it.message }
        assertTrue(withSize.containsKey("blocks[0].size"))
        val onFirst = TemplateValidator.validate(TemplateLoader.parse(yaml(ok, flowAttrs = "    when: first\n"))).associate { it.path to it.message }
        assertTrue(onFirst.containsKey("blocks[0].when"))

        val inSet = """
            blocksets:
              s:
                blocks:
                  - id: body
                    type: flow
                    size: {width: 185, height: 50}
                    table:
                      rowHeight: 8
                      columns: [{id: a, width: 185}]
                      row: {cells: {a: ~}}
            blocks:
              - {id: i, use: s}
        """.trimIndent()
        val e = TemplateValidator.validate(TemplateLoader.parse(inSet)).associate { it.path to it.message }
        assertTrue("only at the top level" in e.getValue("blocksets.s.blocks[0].table"))
    }

    @Test
    fun `only one flow table per template`() {
        val text = yaml(ok) + "\n  - id: body2\n    type: flow\n    table:\n" + ok.trimIndent().prependIndent("      ")
        val e = TemplateValidator.validate(TemplateLoader.parse(text)).associate { it.path to it.message }
        assertTrue("only one flow table" in e.getValue("blocks[1].table"))
    }

    @Test
    fun `flow still does not reserve space or take attach`() {
        val withReserves = TemplateValidator.validate(TemplateLoader.parse(yaml(ok, flowAttrs = "    reserves: true\n"))).associate { it.path to it.message }
        assertTrue(withReserves.containsKey("blocks[0].reserves"))
    }

    @Test
    fun `resolver still fills the flow region for a flow with a table`() {
        val t = TemplateResolver.resolve(TemplateLoader.load(yaml(ok)), PageKind.FIRST)
        assertEquals(t.flowRegion, t.block("body")!!.rect)
        assertEquals(185, t.flowRegion.width.toMillimeters().toInt())
    }

    @Test
    fun `contract checks row binds against the item schema`() {
        val template = TemplateLoader.load(yaml(ok))
        assertEquals(emptyList(), TemplateContract.check(template, itemSchema))

        val bad = TemplateLoader.load(yaml(ok.replace("item.position", "item.postion")))
        val e = TemplateContract.check(bad, itemSchema).associate { it.path to it.message }
        assertTrue("did you mean 'item.position'" in e.getValue("blocks[0].table.row.cells.pos.bind"), e.toString())

        // no `item` declared at all
        val none = TemplateContract.check(template, dataSchema { doc { string("x") } })
        assertEquals(setOf("blocks[0].table.groupBy.field", "blocks[0].table.row.cells.pos.bind", "blocks[0].table.row.cells.name.bind"), none.map { it.path }.toSet())
    }

    @Test
    fun `contract rejects a non scalar item field and a format that does not fit`() {
        val schema = dataSchema { item { record("position") { string("x") }; string("name") } }
        val e = TemplateContract.check(TemplateLoader.load(yaml(ok)), schema).associate { it.path to it.message }
        assertTrue("not a scalar" in e.getValue("blocks[0].table.row.cells.pos.bind"))

        val fmt = TemplateLoader.load(yaml(ok.replace("""{bind: "${'$'}{item.name}", optional: true, align: center}""", """{bind: "${'$'}{item.name}", format: {pattern: "0.0"}}""")))
        val e2 = TemplateContract.check(fmt, itemSchema).associate { it.path to it.message }
        assertTrue(e2.containsKey("blocks[0].table.row.cells.name.format"), e2.toString())
    }
}
