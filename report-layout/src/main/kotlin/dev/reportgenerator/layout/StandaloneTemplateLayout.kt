package dev.reportgenerator.layout

import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.TemplateResolver

/**
 * Layout for a report whose static chrome is a standalone YAML template rather than the named
 * ESKD slots. The normal engine performs pagination and reservations; this pass paints every
 * template block (frame, side fields and title block) with final page.number/page.total values.
 */
fun layOutStandaloneTemplate(
    document: IrDocument,
    textMeasurer: TextMeasurer,
    fontResolver: (TextStyle) -> FontRef,
): LaidOutDocument {
    val template = requireNotNull(document.pageSetup.staticTemplate) { "standalone template is missing" }
    val data = requireNotNull(document.pageSetup.dataContext) { "standalone template data context is missing" }
    val flow = layOut(document, textMeasurer, fontResolver)
    val total = flow.pages.size
    return LaidOutDocument(flow.pages.map { page ->
        val kind = if (page.number == 1) PageKind.FIRST else PageKind.REST
        val chrome = layOutTemplate(
            TemplateResolver.resolve(template, kind),
            data,
            textMeasurer,
            fontResolver,
            page.number,
            total,
        )
        page.copy(elements = page.elements + chrome.elements)
    })
}

