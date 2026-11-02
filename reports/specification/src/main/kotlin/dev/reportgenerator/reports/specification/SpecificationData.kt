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
    val kits: List<KitItem> = emptyList(),
    // Переменные данные исполнений: блоки в порядке связи, основное исполнение первым. Пусто, если исполнений нет
    // или ни в одном исполнении нет уникальных элементов.
    val variants: List<SpecificationVariant> = emptyList(),
    // Notes of the kits with a purpose (link attribute "Назначение комплекта"): one text per kit and purpose, from the
    // root and every execution. They go to the tail of the table, after the variants part.
    val kitNotes: List<String> = emptyList(),
    // Блоки комплектов без назначения и без Спецификации: состав каждого комплекта, после строк комплектов со
    // Спецификацией. Пусто, если таких комплектов нет.
    val kitBlocks: List<KitBlock> = emptyList(),
)

// One execution (исполнение) of the product in the "Переменные данные для исполнений" section: its designation and the
// items that are not common to all executions (the same sections as the main part).
data class SpecificationVariant(
    val designation: String,
    val documentation: List<DocumentationItem> = emptyList(),
    val assemblies: List<AssemblyItem> = emptyList(),
    val parts: List<PartItem> = emptyList(),
    val standards: List<StandardItem> = emptyList(),
    val others: List<OtherItem> = emptyList(),
    val materials: List<MaterialItem> = emptyList(),
    val kits: List<KitItem> = emptyList(),
    val kitBlocks: List<KitBlock> = emptyList(),
) {
    fun isEmpty(): Boolean = documentation.isEmpty() && assemblies.isEmpty() && parts.isEmpty() &&
        standards.isEmpty() && others.isEmpty() && materials.isEmpty() && kits.isEmpty() && kitBlocks.isEmpty()
}

// Common part of the items that take a row of a section. `position` = null when the item has no position.
sealed interface PositionedItem {
    val position: Int?
    val designation: String?
    val name: String
}

data class DocumentationItem(
    val designation: String?,
    val name: String,
    val format: String? = null,
    val notes: List<String> = emptyList(),
)

// `format` / `zone` = null when the item has none. A complex format (with the letter x) and a complex zone (several
// zones, comma-separated) are the mark "*)" here, their value follows the mark in `notes` ("*)А3х3"): the format
// first, then the zone. `notes` = one line per note, the unit of a material is the first of them.
// `quantity` = null when the cell is empty: an item of a permissible replacement group that is non-basic (its count
// is the first note, its replacement texts the last ones).
data class AssemblyItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val format: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class PartItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val format: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class StandardItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class OtherItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

data class MaterialItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

// A kit with a specification: the same cells as an assembly unit; the format is the format of the kit's specification.
data class KitItem(
    override val position: Int? = null,
    override val designation: String? = null,
    override val name: String,
    val quantity: Double?,
    val zone: String? = null,
    val format: String? = null,
    val notes: List<String> = emptyList(),
) : PositionedItem

// A kit without the purpose and without a specification: its composition as a block of the kits section. `label` is the
// name of the kit; the sections are the sections of the composition (a kit inside the kit is not shown).
data class KitBlock(
    val label: String,
    val documentation: List<DocumentationItem> = emptyList(),
    val assemblies: List<AssemblyItem> = emptyList(),
    val parts: List<PartItem> = emptyList(),
    val standards: List<StandardItem> = emptyList(),
    val others: List<OtherItem> = emptyList(),
    val materials: List<MaterialItem> = emptyList(),
) {
    fun isEmpty(): Boolean = documentation.isEmpty() && assemblies.isEmpty() && parts.isEmpty() &&
        standards.isEmpty() && others.isEmpty() && materials.isEmpty()
}
