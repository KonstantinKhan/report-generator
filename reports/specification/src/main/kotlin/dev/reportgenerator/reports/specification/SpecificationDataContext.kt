package dev.reportgenerator.reports.specification

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

// One record per item, the sections in the order of the sheet and the items of a section in their data order.
// Fields without a value (position of documentation, quantity of documentation, unit of a non-material) are left out:
// the template marks their cells `optional` or does not bind them.
fun SpecificationData.itemRecords(): List<DataValue.Record> = rows().map { row ->
    dataContext { item { itemFields(row) } }.get("item") as DataValue.Record
}

// Section of a row: the template groups the rows by it, in the order of GOST R 2.106-2019 p.1.
private enum class SpecificationSection { DOCUMENTATION, ASSEMBLY, PART, STANDARD, OTHER, MATERIAL }

// One row of the sheet, the fields of all sections in one shape (the section tells which of them are set).
private class ItemRow(
    val section: SpecificationSection,
    val position: Int? = null,
    val designation: String? = null,
    val name: String,
    val quantity: Double? = null,
    val unit: String? = null,
    val format: String? = null,
    val notes: List<String> = emptyList(),
)

private fun SpecificationData.rows(): List<ItemRow> =
    documentation.map {
        ItemRow(SpecificationSection.DOCUMENTATION, designation = it.designation, name = it.name)
    } + assemblies.map {
        ItemRow(SpecificationSection.ASSEMBLY, it.position, it.designation, it.name, it.quantity, format = it.format, notes = it.notes)
    } + parts.map {
        ItemRow(SpecificationSection.PART, it.position, it.designation, it.name, it.quantity, format = it.format, notes = it.notes)
    } + standards.map {
        ItemRow(SpecificationSection.STANDARD, it.position, it.designation, it.name, it.quantity, notes = it.notes)
    } + others.map {
        ItemRow(SpecificationSection.OTHER, it.position, it.designation, it.name, it.quantity, notes = it.notes)
    } + materials.map {
        ItemRow(SpecificationSection.MATERIAL, it.position, it.designation, it.name, it.quantity, unit = it.unit, notes = it.notes)
    }

private fun RecordBuilder.itemFields(row: ItemRow?) {
    integer("position", row?.position?.toLong())
    string("designation", row?.designation)
    string("name", row?.name)
    enum("section", SpecificationSection.entries.map { it.name }, row?.section?.name)
    // BigDecimal.valueOf(double): the shortest decimal text of the double, as the legacy formatter used it
    decimal("quantity", row?.quantity?.let { BigDecimal.valueOf(it) })
    string("unit", row?.unit)
    string("format", row?.format)
    // The template has no list binds: the notes of a row are one text, one note per line.
    string("notes", row?.notes?.takeIf { it.isNotEmpty() }?.joinToString("\n"))
}
