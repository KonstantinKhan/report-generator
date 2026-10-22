package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.loodsman.normalizeForComparison
import org.slf4j.Logger

class KindMapper(private val config: MappingConfig, private val log: Logger) {

    fun map(pdmTypeName: String?): PdmNodeKind? {
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
                    PdmNodeKind.Unmapped(normalizedName)
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
}
