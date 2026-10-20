package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.ProductKind
import org.slf4j.Logger

class KindMapper(private val config: MappingConfig, private val log: Logger) {

    fun map(pdmTypeName: String?): ProductKind? {
        if (pdmTypeName == null) {
            return null
        }

        val normalizedName = pdmTypeName.trim()
        val mapped = config.kinds.entries.find { (key, _) ->
            normalizeForComparison(key) == normalizeForComparison(normalizedName)
        }?.value

        return if (mapped != null) {
            Registries.itemKinds.resolve(mapped)
        } else {
            when (config.onUnmapped.kind) {
                OnUnmapped.KEEP -> {
                    log.warn("Unknown item kind '$normalizedName', keeping as unmapped")
                    ProductKind.Unmapped(normalizedName)
                }
                OnUnmapped.SKIP -> {
                    null
                }
                OnUnmapped.FAIL -> {
                    throw IllegalArgumentException(
                        "Unknown item kind '$normalizedName'. Known: ${config.kinds.keys.joinToString(", ")}"
                    )
                }
            }
        }
    }

    private fun normalizeForComparison(name: String): String {
        return name.lowercase().replace('ё', 'е').replace("\\s+".toRegex(), " ").trim()
    }
}
