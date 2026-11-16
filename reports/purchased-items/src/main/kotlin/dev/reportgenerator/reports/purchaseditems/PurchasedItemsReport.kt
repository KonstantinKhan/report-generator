package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.Template
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.PageKind

private val template: Template by lazy {
    val yaml = requireNotNull(object {}.javaClass.getResourceAsStream("/purchased-items.yaml")) {
        "resource missing: /purchased-items.yaml"
    }.readBytes().toString(Charsets.UTF_8)
    TemplateLoader.load(yaml, Styles.named.keys)
}

fun purchasedItemsReport(data: PurchasedItemsData): IrDocument {
    val context = data.toDataContext()
    val index = template.blocks.indexOfFirst { it is FlowBlock && it.table != null }
    require(index >= 0) { "purchased-items.yaml has no flow table" }
    val flow = (template.blocks[index] as FlowBlock).table!!
    val first = TemplateResolver.resolve(template, PageKind.FIRST)
    val margins = template.sheet.margins
    return IrDocument(
        PageSetup(
            format = first.sheet.format,
            margins = Insets(margins.top.mm, margins.right.mm, margins.bottom.mm, margins.left.mm),
            dataContext = context,
            staticTemplate = template,
            bindStaticSlots = false,
        ),
        listOf(FlowTables.build(flow, context.schema, data.itemRecords(), "blocks[$index].table")),
    )
}

