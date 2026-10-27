package dev.reportgenerator.reports.specification

// Data of the specification sheet, one list per section. Each section has its own item type: the fields a section
// shows differ (documentation has no position and no quantity, assemblies and parts have a format).
data class SpecificationData(
    val documentDesignation: String,
    val documentName: String,
    val documentation: List<DocumentationItem> = emptyList(),
    val assemblies: List<AssemblyItem> = emptyList(),
    val parts: List<PartItem> = emptyList(),
    val standards: List<StandardItem> = emptyList(),
    val others: List<OtherItem> = emptyList(),
    val materials: List<MaterialItem> = emptyList(),
)

// Common part of the items that take a row of a section. `position` = null when the item has no position.
sealed interface PositionedItem {
    val position: Int?
    val designation: String?
    val name: String
}

data class DocumentationItem(
    val designation: String?,
    val name: String,
)

// `format` = null when the item has no format. A complex format (with the letter x) is the mark "*)" here and its
// value is the first of `notes`. `notes` = one line per note, the unit of a material is the first of them.
data class AssemblyItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double,
    val format: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class PartItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double,
    val format: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class StandardItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class OtherItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class MaterialItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double,
    val notes: List<String> = emptyList(),
) : PositionedItem
