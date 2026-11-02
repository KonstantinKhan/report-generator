package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import org.slf4j.Logger

class KindMapper(private val config: MappingConfig, private val log: Logger) {

    fun map(pdmTypeName: String?): PdmNodeKind? {
        if (pdmTypeName == null) {
            return null
        }

        val mapped = config.kinds[pdmTypeName]

        return if (mapped != null) {
            Registries.itemKinds.resolve(mapped)
        } else {
            when (config.onUnmapped.kind) {
                OnUnmapped.KEEP -> {
                    log.warn("Unknown item kind '$pdmTypeName', keeping as unmapped")
                    PdmNodeKind.Unmapped(pdmTypeName)
                }
                OnUnmapped.SKIP -> {
                    null
                }
                OnUnmapped.FAIL -> {
                    throw IllegalArgumentException(
                        "Unknown item kind '$pdmTypeName'. Known: ${config.kinds.keys.joinToString(", ")}"
                    )
                }
            }
        }
    }
}
