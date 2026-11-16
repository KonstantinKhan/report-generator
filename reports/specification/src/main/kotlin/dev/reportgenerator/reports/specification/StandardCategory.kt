package dev.reportgenerator.reports.specification

// Категории стандартов по префиксу обозначения стандарта; порядок категорий = порядок в списке.
// Общее для сортировки (SpecificationOrder) и заголовков подгрупп (ProductKindPlurals).
private val standardCategories = listOf("ГОСТ", "ГОСТ Р", "ОСТ", "ТУ", "DIN")
private val standardPrefixesLongestFirst = standardCategories.sortedByDescending { it.length }

internal const val UNKNOWN_STANDARD_CATEGORY = 5

// Индекс категории в порядке сортировки; UNKNOWN_STANDARD_CATEGORY - категория не распознана.
// "ГОСТ Р" проверяется раньше "ГОСТ" (длинный префикс first); после префикса должен идти не-буквенный символ или конец.
internal fun standardCategoryIndex(designation: String?): Int {
    val text = designation?.trim()?.uppercase().orEmpty()
    val prefix = standardPrefixesLongestFirst.firstOrNull { p ->
        text.startsWith(p) && text.getOrNull(p.length)?.isLetter() != true
    } ?: return UNKNOWN_STANDARD_CATEGORY
    return standardCategories.indexOf(prefix)
}
