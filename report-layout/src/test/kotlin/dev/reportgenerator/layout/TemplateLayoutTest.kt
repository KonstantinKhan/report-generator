package dev.reportgenerator.layout

import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.PositionedText
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.dataContext
import java.math.BigDecimal
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TemplateLayoutTest {
    private lateinit var measurer: PdfBoxTextMeasurer
    private val fonts: (TextStyle) -> FontRef = { FontRef("stub") }

    @BeforeTest
    fun setUp() {
        val registry = FontRegistry()
        val ref = registry.register("pt-sans", requireNotNull(javaClass.getResourceAsStream("/fonts/PT_Sans-Regular.ttf")).readBytes())
        measurer = PdfBoxTextMeasurer(registry) { ref }
    }

    private val yaml = """
        blocks:
          - {id: a, type: text, bind: "${'$'}{doc.name}", size: {width: 60, height: 8}}
          - {id: b, type: text, bind: "${'$'}{doc.mass}", format: {pattern: "0.0", locale: ru}, size: {width: 60, height: 8}, attach: {to: a.bottomLeft}}
          - {id: c, type: text, bind: "${'$'}{page.number}", size: {width: 20, height: 8}, attach: {to: b.bottomLeft}}
          - {id: d, type: text, bind: "${'$'}{page.total}", size: {width: 20, height: 8}, attach: {to: c.bottomLeft}}
          - {id: e, type: text, bind: "${'$'}{doc.note}", optional: true, size: {width: 20, height: 8}, attach: {to: d.bottomLeft}}
    """.trimIndent()

    private val data = dataContext {
        doc {
            string("name", "Вал")
            decimal("mass", BigDecimal("1.25"))
            string("note")
        }
    }

    private fun texts(data: dev.reportgenerator.template.DataContext, number: Int, count: Int) =
        layOutTemplate(TemplateResolver.resolve(TemplateLoader.load(yaml), PageKind.FIRST), data, measurer, fonts, number, count)
            .elements.filterIsInstance<PositionedText>().map { it.text }

    @Test
    fun `binds render with format, optional empty and page overlay`() {
        assertEquals(listOf("Вал", "1,3", "2", "7"), texts(data, 2, 7))
    }

    @Test
    fun `missing value of a mandatory bind fails`() {
        val noName = dataContext { doc { string("name"); decimal("mass", BigDecimal.ONE) } }
        assertFailsWith<IllegalStateException> { texts(noName, 1, 1) }
    }
}
