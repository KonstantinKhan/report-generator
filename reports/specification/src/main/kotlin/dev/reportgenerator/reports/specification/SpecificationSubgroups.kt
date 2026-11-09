package dev.reportgenerator.reports.specification

// Sub-title of a group of the standard or other products: the product kind in the plural. The sub-title exists only for a
// recognized category of the standard and a product kind from the dictionary product-kind-plurals.yaml, otherwise null.
internal object SpecificationSubgroups {
    private const val RESOURCE = "product-kind-plurals.yaml"

    // Dictionary: product kind (lower case, trimmed) -> plural.
    private val plurals: Map<String, String> by lazy { parse(load()) }

    fun of(item: CatalogItem): String? {
        if (standardCategoryIndex(item.standardDesignation) == UNKNOWN_STANDARD_CATEGORY) return null
        return plural(item.productKind)
    }

    fun plural(productKind: String?): String? = plurals[productKind?.trim()?.lowercase() ?: return null]

    private fun load(): String =
        (SpecificationSubgroups::class.java.getResourceAsStream("/$RESOURCE") ?: error("resource $RESOURCE not found"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    // Flat YAML "key: value" per line, # is a comment. The kaml parser is not a compile dependency of the module.
    internal fun parse(text: String): Map<String, String> = buildMap {
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val separator = line.indexOf(':')
            require(separator > 0) { "$RESOURCE: expected 'key: value', got '$line'" }
            val key = line.substring(0, separator).trim().trim('"').lowercase()
            val value = line.substring(separator + 1).trim().trim('"')
            require(key.isNotEmpty() && value.isNotEmpty()) { "$RESOURCE: empty key or value in '$line'" }
            put(key, value)
        }
    }
}
