package dev.reportgenerator.reports.specification

import dev.reportgenerator.data.ItemKind
import dev.reportgenerator.data.SpecificationData
import dev.reportgenerator.data.SpecificationItem
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext
import java.math.BigDecimal

// Adapter SpecificationData -> DataContext (schema + values) for the template binds of the specification
// sheet. Extend by adding fields to `doc { }`: the schema is declared by the same builder that supplies the values.
// `item { }` declares (schema only, no values) the RAW row record the flow table of gost-spec.yaml reads:
// the YAML decides the grouping, numbering and how a value is shown. The values of each row come from itemRecords().
fun SpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
    }
    item { itemFields(null) }
}

// One record per item, in source order. Fields without a value (designation of a standard item, unit of a
// part) are left out: the template marks their cells `optional`.
fun SpecificationData.itemRecords(): List<DataValue.Record> = items.map { itemRecord(it) }

private fun RecordBuilder.itemFields(item: SpecificationItem?) {
    string("designation", item?.designation)
    string("name", item?.name)
    enum("kind", ItemKind.entries.map { it.name }, item?.kind?.name)
    // BigDecimal.valueOf(double): the shortest decimal text of the double, as the legacy formatter used it
    decimal("quantity", item?.let { BigDecimal.valueOf(it.quantity) })
    string("unit", item?.unit)
}

private fun itemRecord(item: SpecificationItem): DataValue.Record =
    dataContext { item { itemFields(item) } }.get("item") as DataValue.Record
