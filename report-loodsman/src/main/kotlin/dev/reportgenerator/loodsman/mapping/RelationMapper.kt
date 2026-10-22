package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.Relation
import dev.reportgenerator.loodsman.normalizeForComparison
import org.slf4j.Logger

class RelationMapper(private val config: MappingConfig, private val log: Logger) {

    fun map(pdmRelationName: String?): Relation? {
        if (pdmRelationName == null) {
            return null
        }

        val normalizedName = pdmRelationName.trim()
        val mapped = config.relations.entries.find { (key, _) ->
            normalizeForComparison(key) == normalizeForComparison(normalizedName)
        }?.value

        return if (mapped != null) {
            Registries.relations.resolve(mapped)
        } else {
            when (config.onUnmapped.relation) {
                OnUnmapped.KEEP -> {
                    log.warn("Unknown relation '$normalizedName', keeping as unmapped")
                    Relation.Unmapped(normalizedName)
                }
                OnUnmapped.SKIP -> {
                    null
                }
                OnUnmapped.FAIL -> {
                    throw IllegalArgumentException(
                        "Unknown relation '$normalizedName'. Known: ${config.relations.keys.joinToString(", ")}"
                    )
                }
            }
        }
    }

    fun pdmNameOf(relation: Relation): String? {
        return when (relation) {
            is Relation.Unmapped -> relation.pdmName
            else -> config.relations.entries.find { (_, value) ->
                Registries.relations.resolve(value) == relation
            }?.key
        }
    }
}
