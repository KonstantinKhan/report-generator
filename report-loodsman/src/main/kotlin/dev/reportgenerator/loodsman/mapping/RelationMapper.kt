package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.Relation
import org.slf4j.Logger

class RelationMapper(private val config: MappingConfig, private val log: Logger) {

    /**
     * Relation по имени типа связи Loodsman. Учитываются правила любого направления;
     * при совпадении имени приоритет у FORWARD.
     */
    fun map(pdmRelationName: String?): Relation? {
        if (pdmRelationName == null) {
            return null
        }

        val rule = preferredRule { it.link == pdmRelationName }

        return if (rule != null) {
            Registries.relations.resolve(rule.relation)
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
                        "Unknown relation '$pdmRelationName'. Known: ${config.relations.map { it.link }.distinct().joinToString(", ")}"
                    )
                }
            }
        }
    }

    fun pdmNameOf(relation: Relation): String? {
        return when (relation) {
            is Relation.Unmapped -> relation.pdmName
            else -> preferredRule { Registries.relations.resolve(it.relation) == relation }?.link
        }
    }

    /** Все правила, чей relation входит в набор, с уже разрешённым смыслом. */
    fun rulesFor(relations: Set<Relation>): List<ResolvedRelationRule> =
        config.relations
            .map { rule ->
                ResolvedRelationRule(
                    link = rule.link,
                    direction = rule.direction,
                    from = rule.from.toSet(),
                    relation = Registries.relations.resolve(rule.relation),
                )
            }
            .filter { it.relation in relations }

    private fun preferredRule(predicate: (RelationRule) -> Boolean): RelationRule? =
        config.relations.filter(predicate).sortedBy { it.direction != LinkDirection.FORWARD }.firstOrNull()
}

data class ResolvedRelationRule(
    val link: String,
    val direction: LinkDirection,
    val from: Set<String>,
    val relation: Relation,
) {
    fun appliesTo(typeName: String): Boolean = from.isEmpty() || typeName in from
}
