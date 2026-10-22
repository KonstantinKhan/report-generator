package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.loodsman.MetadataSnapshot
import dev.reportgenerator.loodsman.normalizeForComparison

/**
 * Семантическая проверка конфига маппинга против снимка метаданных Loodsman.
 * Собирает все ошибки сразу и не бросает исключений.
 * Типы объектов и связи сравниваются мягко ([normalizeForComparison]), атрибуты — строго.
 * Структурная проверка конфига выполняется раньше, в [MappingConfig.validate].
 */
object MappingConfigValidator {

    fun validate(config: MappingConfig, snapshot: MetadataSnapshot): List<String> {
        val objectTypes = snapshot.objectTypes.mapTo(HashSet()) { normalizeForComparison(it.typeName.orEmpty()) }
        val linkTypes = snapshot.linkTypes.mapTo(HashSet()) { normalizeForComparison(it.name.orEmpty()) }
        val attributes = snapshot.attributeNames
        val errors = mutableListOf<String>()

        config.kinds.keys.forEach { kind ->
            if (normalizeForComparison(kind) !in objectTypes) {
                errors += "kinds: type '$kind' not found in Loodsman"
            }
        }

        config.relations.keys.forEach { relation ->
            if (normalizeForComparison(relation) !in linkTypes) {
                errors += "relations: link type '$relation' not found in Loodsman"
            }
        }

        config.requiredRelations.forEach { requiredId ->
            val pdmNames = config.relations.filterValues { it == requiredId }.keys
            if (pdmNames.none { normalizeForComparison(it) in linkTypes }) {
                errors += "requiredRelations: '$requiredId' has no link type in Loodsman, tried: ${pdmNames.joinToString()}"
            }
        }

        val identity = config.identity
        errors.checkAttribute("identity.designation", identity.designation.attributeName(), attributes)
        errors.checkAttribute("identity.name", identity.name.attributeName(), attributes)
        identity.byKind.forEach { (kind, kindIdentity) ->
            errors.checkAttribute("identity.byKind.$kind.designation", kindIdentity.designation.attributeName(), attributes)
            errors.checkAttribute("identity.byKind.$kind.name", kindIdentity.name.attributeName(), attributes)
        }

        config.attributes.forEach { (key, rule) ->
            errors.checkAttribute("attributes.$key.name", rule.name, attributes)
            rule.byKind.forEach { (kind, name) ->
                errors.checkAttribute("attributes.$key.byKind.$kind", name, attributes)
            }
        }

        config.link.attributes.forEach { (key, rules) ->
            rules.forEachIndexed { index, rule ->
                errors.checkAttribute("link.attributes.$key[$index].name", rule.name, attributes)
            }
        }

        return errors
    }

    private fun FieldSource?.attributeName(): String? = (this as? FieldSource.Attribute)?.name

    private fun MutableList<String>.checkAttribute(path: String, name: String?, attributes: Set<String>) {
        if (name != null && name !in attributes) {
            add("$path: attribute '$name' not found in Loodsman")
        }
    }
}
