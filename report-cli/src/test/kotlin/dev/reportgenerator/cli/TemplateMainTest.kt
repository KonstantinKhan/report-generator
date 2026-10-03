package dev.reportgenerator.cli

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
}
