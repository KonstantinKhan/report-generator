package dev.reportgenerator.reports.specification

import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.RecordBuilder
import dev.reportgenerator.template.dataContext

// Adapter SpecificationListData -> DataContext (schema + values) for the template of the specification list sheet
// (gost-spec-list.yaml). The item record is one row of the sheet: the cell "Куда входит" is two columns (the designation and
// the quantity of the parents), each line of it is one text line of the same cell, separated by "\n" (one physical line of
// the flow table per parent). The record is built here; the YAML decides the grouping and how a value is shown.
fun SpecificationListData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
        // null = no value: the bind of the customer representative table is `optional`, its cell stays empty.
        string("customerIndex", customerIndex)
    }
    item { listItemFields(null) }
}

// The records of the rows in the order of the sheet. The "№ строки" column is not data: the engine numbers the physical
// lines of the records (`${line.number}` in gost-spec-list.yaml), so the record has no number.
fun SpecificationListData.itemRecords(): List<DataValue.Record> = rows.map { row ->
    dataContext { item { listItemFields(row) } }.get("item") as DataValue.Record
}

// The sections of the specification list, in the order of the sheet (the assemblies, then the kits).
private val LIST_SECTIONS = listOf(SpecificationSection.ASSEMBLY, SpecificationSection.KIT)

// The fields of one row: the designation and the name of the object, the parents (one line each: the designation and
// the quantity), the total quantity (the first line only) and the notes (empty in this sheet).
// `section` is the section of the row (groupBy in the YAML); the values are null when the row is not given (schema only).
internal fun RecordBuilder.listItemFields(row: SpecificationListRow?) {
    // the sections of the sheet only (the template's groupBy order): the list has no other kinds of rows
    enum("section", LIST_SECTIONS.map { it.name }, row?.section?.name)
    string("designation", row?.designation)
    string("name", row?.name)
    string("parentDesignation", row?.parents?.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.designation })
    string("parentQuantity", row?.parents?.takeIf { it.isNotEmpty() }?.joinToString("\n") { formatQuantity(it.quantity, false) })
    string("total", row?.total?.let { formatQuantity(it, false) })
    string("notes", null)
}
