package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.loodsman.MetadataSnapshot

/**
 * Семантическая проверка конфига маппинга против снимка метаданных Loodsman.
 * Собирает все ошибки сразу и не бросает исключений.
 * Типы объектов, связи и атрибуты сравниваются строго, как есть (`==`), без модификаций имён.
 * Структурная проверка конфига выполняется раньше, в [MappingConfig.validate].
 */
object MappingConfigValidator {

    fun validate(config: MappingConfig, snapshot: MetadataSnapshot): List<String> {
        val objectTypes = snapshot.objectTypes.mapNotNullTo(HashSet()) { it.typeName }
        val linkTypes = snapshot.linkTypes.mapNotNullTo(HashSet()) { it.name }
        val attributes = snapshot.attributeNames
        val errors = mutableListOf<String>()

        config.kinds.keys.forEach { kind ->
            if (kind !in objectTypes) {
                errors += "kinds: type '$kind' not found in Loodsman. ${available(objectTypes)}"
                return@forEach
            }
            val keyAttribute = snapshot.keyAttributeOf(kind)
            if (keyAttribute == null) {
                errors += "kinds: type '$kind' has no key attribute in Loodsman"
            } else if (keyAttribute !in attributes) {
                errors += "kinds: key attribute '$keyAttribute' of type '$kind' not found among Loodsman attributes"
            }
        }

        config.relations.forEach { rule ->
            if (rule.link !in linkTypes) {
                errors += "relations: link type '${rule.link}' not found in Loodsman. ${available(linkTypes)}"
            }
            rule.from.forEach { type ->
                if (type !in objectTypes) {
                    errors += "relations: from type '$type' of link '${rule.link}' not found in Loodsman. ${available(objectTypes)}"
                }
            }
        }

        config.relations.forEach { rule ->
            val linkTypeId = snapshot.linkTypeId(rule.link) ?: return@forEach
            rule.from.filter { it in objectTypes }.forEach { type ->
                val allowed = snapshot.linkDirections(type, linkTypeId)
                val permitted = when (rule.direction) {
                    LinkDirection.FORWARD -> 1 in allowed || 0 in allowed
                    LinkDirection.INVERSE -> -1 in allowed || 0 in allowed
                }
                if (!permitted) {
                    errors += "правило '${rule.link}': тип '$type' не может быть источником связи в направлении ${rule.direction} по метаданным Loodsman"
                }
            }
        }

        config.requiredRelations.forEach { requiredId ->
            val pdmNames = config.relations.filter { it.relation == requiredId }.map { it.link }.distinct()
            if (pdmNames.none { it in linkTypes }) {
                errors += "requiredRelations: '$requiredId' has no link type in Loodsman, tried: ${pdmNames.joinToString()}. ${available(linkTypes)}"
            }
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

    /** Список доступных имён из Loodsman, отсортированный, чтобы пользователь скопировал точное имя. */
    private fun available(names: Set<String>): String =
        "Available: ${names.sorted().joinToString()}"

    private fun MutableList<String>.checkAttribute(path: String, name: String?, attributes: Set<String>) {
        if (name != null && name !in attributes) {
            add("$path: attribute '$name' not found in Loodsman")
        }
    }
}
