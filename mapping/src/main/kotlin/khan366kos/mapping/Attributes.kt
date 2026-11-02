package khan366kos.mapping

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

fun readAttributes(
    props: PropObjectOutputDto,
    raw: List<ObjectAttributeOutputDto>,
    request: AttributeRequest,
): Map<AttributeKey, AttributeValue> {
    val result = LinkedHashMap<AttributeKey, AttributeValue>()
    request.names.forEach { (key, name) ->
        val value = name?.let { readAttribute(it, request.keyAttribute, props, raw) }
        result[key] = value?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
    }
    if (request.includeUnmapped) {
        val excluded = request.mappedNames + listOfNotNull(request.keyAttribute)
        raw.forEach { attribute ->
            val name = attribute.name
            val value = attribute.value
            if (!name.isNullOrEmpty() && name !in excluded && !value.isNullOrEmpty()) {
                result[AttributeKey.Unmapped(name)] = AttributeValue.Text(value)
            }
        }
    }
    return result
}
