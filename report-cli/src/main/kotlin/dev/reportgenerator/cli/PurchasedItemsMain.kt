package dev.reportgenerator.cli

import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOutStandaloneTemplate
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.reports.purchaseditems.PurchasedItemRow
import dev.reportgenerator.reports.purchaseditems.PurchasedItemGroup
import dev.reportgenerator.reports.purchaseditems.PurchasedItemsData
import dev.reportgenerator.reports.purchaseditems.purchasedItemsReport
import java.io.File
import java.math.BigDecimal

fun main() {
    val output = File("output/purchased-items-production").apply { mkdirs() }
    val data = fixture()
    val fonts = DefaultFontRegistry.load()
    val measured = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)
    val laidOut = layOutStandaloneTemplate(purchasedItemsReport(data), measured, fonts::resolve)
    render(laidOut).forEachIndexed { index, svg ->
        File(output, "purchased-items-page-${index + 1}.svg").writeText(svg)
    }
    File(output, "purchased-items.pdf").writeBytes(renderToPdf(laidOut, fonts.registry))
    println("wrote ${File(output, "purchased-items.pdf").absolutePath}")
}

private fun fixture(): PurchasedItemsData {
    val rows = (1..38).map { n ->
        PurchasedItemRow(
            itemId = n,
            group = if (n <= 20) PurchasedItemGroup.BOLT else PurchasedItemGroup.OTHER,
            name = when (n) {
                5 -> "Подшипник с очень длинным наименованием для проверки переноса текста внутри графы без выхода за границы"
                22 -> "Bearing SKF 6204-2RS / Подшипник радиальный ISO 15"
                else -> "Болт М${6 + n % 8}x${20 + n} ГОСТ 7798-70"
            },
            productCode = if (n % 7 == 0) null else "128${n.toString().padStart(3, '0')}" ,
            supplyDocument = if (n == 22) "ISO 15 / DIN 625" else "ГОСТ 7798-70",
            supplier = if (n % 5 == 0) null else "Поставщик ${n % 4 + 1}",
            inclusion = if (n % 3 == 0) "078.505.9.0120.00" else "078.505.9.0100.00",
            unit = "pcs",
            quantityOnProduct = BigDecimal.valueOf((n % 9 + 1).toLong()),
            quantityInKits = if (n % 6 == 0) BigDecimal("2") else BigDecimal.ZERO,
            quantityAdjustment = if (n == 1) BigDecimal.ONE else BigDecimal.ZERO,
            note = if (n == 22) "Импортное изделие" else null,
        )
    }
    return PurchasedItemsData(
        documentDesignation = "078.505.9.0100.00 ВП",
        documentName = "Редуктор",
        companyName = "АСКОН",
        developedBy = "Вилеев Д.Л.",
        primaryApplicability = "078.505.9.0100.00",
        version = "3.0",
        state = "Архив",
        lastModifiedNumber = "2",
        rows = rows,
    )
}
