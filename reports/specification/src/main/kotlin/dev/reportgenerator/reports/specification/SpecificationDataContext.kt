package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.data.formattedQuantity
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext

// Adapter SpecificationData -> DataContext (schema + values) for the template binds of the specification
// sheet. Extend by adding fields to `doc { }`: the schema is declared by the same builder that supplies the values.
// `item { }` declares (schema only, no values) the row record the flow table of gost-spec.yaml reads;
// the values of each row come from itemRecord().
fun SpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
    }
    item { itemFields(null) }
}

private fun RecordBuilder.itemFields(item: ItemRow?) {
    integer("position", item?.position?.toLong())
    string("designation", item?.source?.designation)
    string("name", item?.source?.name)
    // TEMPORARY: computed by code (formattedQuantity) until `format` takes over in stage 3
    string("quantityText", item?.source?.formattedQuantity())
    // materials only: other kinds are counted in pieces and get no unit
    string("unit", item?.source?.takeIf { it.kind == ItemKind.MATERIAL }?.unit)
    enum("kind", ItemKind.entries.map { it.name }, item?.source?.kind?.name)
}

private class ItemRow(val source: SpecificationItem, val position: Int)

// One flow table row: fields without a value (designation of a standard item, unit of a non-material) are
// left out, the template marks their cells `optional`.
fun itemRecord(item: SpecificationItem, position: Int): DataValue.Record =
    dataContext { item { itemFields(ItemRow(item, position)) } }.get("item") as DataValue.Record
