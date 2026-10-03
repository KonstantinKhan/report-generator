package dev.reportgenerator.cli

import dev.reportgenerator.ir.Styles
import dev.reportgenerator.template.DataYaml
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.declaredEnumValues
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
    fun `a block named like a static slot still reserves space under the flow table`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val rows = (1..5).joinToString("\n") { "  - {n: $it, name: Деталь $it}" }
        val data = DataYaml.parseFile("doc: {designation: X}\nitem:\n$rows")
        fun render(id: String) = renderTemplate(
            TemplateLoader.load(flowTemplate.replace("id: num", "id: $id"), Styles.named.keys), data.context, 1, dir, data.items
        ).first { it.extension == "svg" }.readText()

        // ids are free outside the spec engine: `stamp` is the same block as `num`, so the same flow region
        assertEquals(render("num"), render("stamp"))
        assertEquals(render("num"), render("belowFrame"))
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

    private fun resource(name: String) = requireNotNull(javaClass.getResourceAsStream("/templates/$name")) { "missing $name" }.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `sample template renders grouped, sorted and numbered rows from a data file`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val template = TemplateLoader.load(resource("purchased-list.yaml"), Styles.named.keys)
        val spec = (template.blocks.single() as dev.reportgenerator.template.FlowBlock).table!!
        val data = DataYaml.parseFile(resource("purchased-list-data.yaml"), mapOf("category" to spec.declaredEnumValues().getValue("category")))

        val svg = renderTemplate(template, data.context, 1, dir, data.items).first { it.extension == "svg" }.readText()
        val texts = Regex(">([^<]+)<").findAll(svg).map { it.groupValues[1] }.toList()

        fun order(vararg xs: String) = xs.map { texts.indexOf(it) }.also { i -> assertTrue(i.all { it >= 0 }, "missing: ${xs.toList()}"); assertEquals(i.sorted(), i, "order of ${xs.toList()}") }
        order("Крепёж", "Подшипники", "Электроизделия")
        // natural order inside a group: Болт М6x8 < М6x20 < М6x100, then the rest by alphabet
        order("Болт М6x8 ГОСТ 7798-70", "Болт М6x20 ГОСТ 7798-70", "Болт М6x100 ГОСТ 7798-70", "Смазка Литол-24", "Шайба 6 ГОСТ 11371-78")
        assertTrue("по запросу" in texts && "12,5" in texts && "0,35" in texts && "3" in texts, "formats and the isNull case")
        dir.deleteRecursively()
    }

    // Worked out by hand from purchased-list-data.yaml (cost = qty * price, kopecks, HALF_UP; no price = no cost):
    // Крепёж 2.5 * 2.8 = 7.00, 24 * 3.2 = 76.80, 8 * 5 = 40.00, 0.35 * 410 = 143.50, 48 * 0.45 = 21.60 -> 288.90;
    // Подшипники 2 * 180 = 360.00, 4 * 312.5 = 1250.00 -> 1610.00; Электроизделия 1 * 640 = 640.00 (the cable has
    // no price) -> 640.00; all together 288.90 + 1610.00 + 640.00 = 2538.90; 9 positions.
    @Test
    fun `sample template shows cost, group subtotals and the grand total`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val template = TemplateLoader.load(resource("purchased-list.yaml"), Styles.named.keys)
        val spec = (template.blocks.single() as dev.reportgenerator.template.FlowBlock).table!!
        val data = DataYaml.parseFile(resource("purchased-list-data.yaml"), mapOf("category" to spec.declaredEnumValues().getValue("category")))

        val svg = renderTemplate(template, data.context, 1, dir, data.items).first { it.extension == "svg" }.readText()
        val texts = Regex(">([^<]+)<").findAll(svg).map { it.groupValues[1] }.toList()

        val costs = listOf("7,00", "76,80", "40,00", "143,50", "21,60", "288,90", "360,00", "1250,00", "1610,00", "640,00", "640,00", "2538,90")
        val tail = texts.dropWhile { it != "Сумма, руб." }.drop(1).filter { it in costs || it.startsWith("Итого") || it == "Всего" }
        assertEquals(
            listOf(
                "7,00", "76,80", "40,00", "143,50", "21,60", "Итого по группе", "288,90",
                "360,00", "1250,00", "Итого по группе", "1610,00",
                "640,00", "640,00", "Итого по группе", "640,00", // the price of the Автомат is 640,00 too
                "Всего", "2538,90"
            ),
            tail
        )
        assertEquals(listOf("Позиций", "9"), texts.filter { it.isNotBlank() }.let { t -> t.drop(t.indexOf("Позиций")).take(2) })
        assertEquals(3, texts.count { it == "Итого по группе" })
        dir.deleteRecursively()
    }

    @Test
    fun `a category the template does not list is a contract error, not a lost row`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val template = TemplateLoader.load(resource("purchased-list.yaml"), Styles.named.keys)
        val data = DataYaml.parseFile(
            "item:\n  - {category: PAINT, name: x, qty: 1, unit: шт}",
            mapOf("category" to listOf("FASTENER", "BEARING", "ELECTRIC"))
        )
        val e = assertFailsWith<TemplateException> { renderTemplate(template, data.context, 1, dir, data.items) }
        assertTrue("blocks[0].table.groupBy.order" in e.errors.map { it.path }, e.toString())
        dir.deleteRecursively()
    }

    // tutorial/09d: 5 records; the note of record 2 wraps into 3 lines, of record 4 into 2 -> 8 physical lines:
    // record 1 -> line 1; record 2 -> 2, 3, 4; record 3 -> 5; record 4 -> 6, 7; record 5 -> 8. Then the filler rows (fill: true)
    // go on with 9 .. 32 (32 rows of 8 mm fit under the 15 mm header above the 15 mm title block). `pos` stays per record.
    private fun lineNumberTexts(svg: String, fromMm: Int): List<String> =
        Regex("""<text x="([\d.]+)" y="([\d.]+)"[^>]*>([^<]+)</text>""").findAll(svg)
            .map { Triple(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3]) }
            .filter { (x, y, _) -> x >= fromMm && x < fromMm + 10 && y > 20 }
            .sortedBy { it.second }.map { it.third }.toList()

    @Test
    fun `tutorial 09d numbers every physical line of a wrapped record through the real engine`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val template = TemplateLoader.load(resource("tutorial/09d-line-numbers.yaml"), Styles.named.keys)
        val data = DataYaml.parseFile(resource("tutorial/09d-line-numbers-data.yaml"))

        val svg = renderTemplate(template, data.context, 1, dir, data.items).filter { it.extension == "svg" }.single().readText()

        assertEquals((1..32).map(Int::toString), lineNumberTexts(svg, 20))
        assertEquals((1..5).map(Int::toString), lineNumberTexts(svg, 30))
        dir.deleteRecursively()
    }

    @Test
    fun `without a lines section fillers stay unnumbered and the numbers still run over wrapped lines`() {
        val dir = Files.createTempDirectory("template-main").toFile()
        val yaml = resource("tutorial/09d-line-numbers.yaml").lines().filterNot { it.trimStart().let { l -> l.startsWith("start:") || l.startsWith("scope:") || l.startsWith("fill: true") || l.startsWith("lines:") } }.joinToString("\n")
        val template = TemplateLoader.load(yaml, Styles.named.keys)
        val data = DataYaml.parseFile(resource("tutorial/09d-line-numbers-data.yaml"))

        val svg = renderTemplate(template, data.context, 1, dir, data.items).filter { it.extension == "svg" }.single().readText()

        assertEquals((1..8).map(Int::toString), lineNumberTexts(svg, 20))
        dir.deleteRecursively()
    }
}
