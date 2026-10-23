package khan366kos.mapping

import dev.reportgenerator.domain.AttributeKey

/**
 * Разрешённое для одного вида чтение атрибутов. Имена уже разрешены клиентом с учётом byKind.
 *
 * @property keyAttribute ключевой атрибут типа (значение в `product`); null — тип без ключа
 * @property keyKey доменный ключ правила, чьё имя совпало с ключевым атрибутом; иначе ключ будет Unmapped
 * @property names запрошенные атрибуты: доменный ключ → имя Loodsman для вида (null — правила для вида нет)
 * @property mappedNames имена правил вида, по которым атрибут уже сопоставлен (для includeUnmapped)
 */
data class AttributeRequest(
    val keyAttribute: String?,
    val keyKey: AttributeKey?,
    val names: Map<AttributeKey, String?>,
    val mappedNames: Set<String> = emptySet(),
    val includeUnmapped: Boolean = false,
) {
    /**
     * Нужен ли список атрибутов объекта. Ключевой атрибут читается из product без списка, поэтому список
     * нужен только для неключевого запрошенного атрибута вида или при includeUnmapped.
     */
    fun needsObjectAttributes(): Boolean = includeUnmapped || names.values.any { name ->
        name != null && name != keyAttribute
    }
}
