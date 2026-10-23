package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.Relation
import org.slf4j.Logger

class RelationMapper(private val config: MappingConfig, private val log: Logger) {

    fun map(pdmRelationName: String?): Relation? {
        if (pdmRelationName == null) {
            return null
        }

        val mapped = config.relations[pdmRelationName]

        return if (mapped != null) {
            Registries.relations.resolve(mapped)
        } else {
            when (config.onUnmapped.relation) {
                OnUnmapped.KEEP -> {
                    log.warn("Unknown relation '$pdmRelationName', keeping as unmapped")
                    Relation.Unmapped(pdmRelationName)
                }
                OnUnmapped.SKIP -> {
                    null
                }
                OnUnmapped.FAIL -> {
                    throw IllegalArgumentException(
                        "Unknown relation '$pdmRelationName'. Known: ${config.relations.keys.joinToString(", ")}"
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
