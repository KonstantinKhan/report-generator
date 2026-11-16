package dev.reportgenerator.reports.purchaseditems

import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext

fun PurchasedItemsData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
        string("companyName", companyName)
        string("developedBy", developedBy)
        string("primaryApplicability", primaryApplicability)
        string("state", state)
        string("version", version)
        string("lastModifiedNumber", lastModifiedNumber)
    }
    item { itemFields(null, PurchasedItemGroup.entries.map { it.name }) }
}

fun PurchasedItemsData.itemRecords(): List<DataValue.Record> {
    val groups = PurchasedItemGroup.entries.map { it.name }
    return rows.map { row ->
        dataContext { item { itemFields(row, groups) } }.get("item") as DataValue.Record
    } + references.map { reference ->
        dataContext { item {
            itemFields(null, groups)
            enum("section", groups, PurchasedItemGroup.DOCUMENT_REFERENCE.name)
            string("name", reference.name)
            string("supplyDocument", reference.documentDesignation)
            decimal("selectionRank", java.math.BigDecimal.ZERO)
        } }.get("item") as DataValue.Record
    }
}

private fun RecordBuilder.itemFields(row: PurchasedItemRow?, groups: List<String>) {
    enum("section", groups, row?.group?.name)
    string("name", row?.name)
    string("productCode", row?.productCode)
    string("supplyDocument", row?.supplyDocument)
    string("supplier", row?.supplier)
    string("inclusion", row?.inclusion)
    // Empty quantity cells match Form 5: zero is absence of a quantity in that column,
    // while a positive value is printed. The arithmetic stays in PurchasedItemRow.
    decimal("onProduct", row?.quantityOnProduct?.takeUnless { it.signum() == 0 })
    decimal("inKits", row?.quantityInKits?.takeUnless { it.signum() == 0 })
    decimal("adjustment", row?.quantityAdjustment?.takeUnless { it.signum() == 0 })
    decimal("total", row?.quantityTotal?.takeUnless { it.signum() == 0 })
    string("note", row?.note)
    decimal("selectionRank", row?.let { if (it.selectionRelated) java.math.BigDecimal.ONE else java.math.BigDecimal.ZERO })
}
