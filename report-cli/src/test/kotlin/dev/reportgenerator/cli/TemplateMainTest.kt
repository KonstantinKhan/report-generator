package dev.reportgenerator.cli

import dev.reportgenerator.ir.Styles
import dev.reportgenerator.template.DataYaml
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateMainTest {
    private val template = """
        name: smoke
        blocks:
          - {id: d, type: text, bind: "${'$'}{doc.designation}", size: {width: 60, height: 8}}
          - {id: m, type: text, bind: "${'$'}{doc.mass}", format: {pattern: "0.00", locale: ru}, size: {width: 60, height: 8}, attach: {to: d.bottomLeft}}
          - {id: p, type: text, bind: "${'$'}{page.number}", size: {width: 20, height: 8}, attach: {to: m.bottomLeft}}
    """.trimIndent()

    @Test
    fun `data file renders with typed values and writes svg and pdf`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val data = DataYaml.parse("doc: {designation: XY.001, mass: 2.5}")
        val files = renderTemplate(TemplateLoader.load(template), data, pages = 2, outputDir = dir)

        assertEquals(listOf("smoke-page-1.svg", "smoke-page-2.svg", "smoke.pdf"), files.map { it.name })
        assertTrue(files.all { it.length() > 0 })
        val svg1 = files[0].readText()
        assertTrue(">XY.001<" in svg1 && ">2,50<" in svg1 && ">1<" in svg1, "page 1 text")
        assertTrue(">2<" in files[1].readText(), "page 2 number")
        dir.deleteRecursively()
    }

    @Test
    fun `data missing a bound path fails the contract before layout`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val data = DataYaml.parse("doc: {designation: XY.001}")
        val e = assertFailsWith<TemplateException> { renderTemplate(TemplateLoader.load(template), data, 1, dir) }
        assertEquals(listOf("blocks[1].bind"), e.errors.map { it.path })
        assertTrue(dir.listFiles().isNullOrEmpty())
        dir.deleteRecursively()
    }

    private val flowTemplate = """
        name: flow-smoke
        sheet: {format: A4, margins: {top: 5, right: 5, bottom: 5, left: 20}}
        blocks:
          - {id: num, type: text, bind: "${'$'}{page.number}", size: {width: 20, height: 8}, attach: {self: bottomRight, to: sheet.contentBottomRight}, reserves: true}
          - id: body
            type: flow
            table:
              rowHeight: 8
              fill: blank
              columns:
                - {id: n, width: 15, align: center}
                - {id: name, width: 170}
              header:
                height: 10
                cells: {n: "№", name: "Наименование"}
              row:
                cells:
                  n: {bind: "${'$'}{item.n}"}
                  name: {bind: "${'$'}{item.name}"}
    """.trimIndent()

    @Test
    fun `flow table renders item rows through the layout engine with pagination`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val rows = (1..80).joinToString("\n") { "  - {n: $it, name: Деталь $it}" }
        val data = DataYaml.parseFile("doc: {designation: X}\nitem:\n$rows")
        assertEquals(80, data.items.size)

        val files = renderTemplate(TemplateLoader.load(flowTemplate, Styles.named.keys), data.context, pages = 1, outputDir = dir, items = data.items)

        val svgs = files.filter { it.extension == "svg" }
        assertTrue(svgs.size >= 3, "80 rows of 8 mm do not fit on 2 pages, got ${svgs.size}")
        assertTrue(files.any { it.name == "flow-smoke.pdf" && it.length() > 0 })
        assertTrue(svgs.all { ">Наименование<" in it.readText() }, "header on every page")
        assertTrue(">Деталь 1<" in svgs.first().readText() && ">Деталь 80<" in svgs.last().readText())
        assertTrue(">${svgs.size}<" in svgs.last().readText(), "page.number of the last page")
        dir.deleteRecursively()
    }

    @Test
    fun `flow row bind missing in the data fails the contract before layout`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val data = DataYaml.parseFile("item:\n  - {n: 1}")
        val e = assertFailsWith<TemplateException> {
            renderTemplate(TemplateLoader.load(flowTemplate, Styles.named.keys), data.context, 1, dir, data.items)
        }
        assertEquals(listOf("blocks[1].table.row.cells.name.bind"), e.errors.map { it.path })
        dir.deleteRecursively()
    }
}
