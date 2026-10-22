package dev.reportgenerator.loodsman

/**
 * Мягкая нормализация имён типов объектов и связей: регистр, «ё» и пробелы не влияют на сравнение.
 * Имена атрибутов этой функцией НЕ нормализуются: они сравниваются строго.
 */
internal fun normalizeForComparison(name: String): String =
    name.lowercase().replace('ё', 'е').replace("\\s+".toRegex(), " ").trim()
