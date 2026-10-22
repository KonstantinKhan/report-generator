package khan366kos.mapping

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.AttributeValue
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto

fun readAttributes(
    raw: List<ObjectAttributeOutputDto>,
    request: AttributeRequest,
    sources: IdentitySources,
): Map<AttributeKey, AttributeValue> {
    val result = LinkedHashMap<AttributeKey, AttributeValue>()
    request.names.forEach { (name, key) ->
        val value = raw.valueOf(name)                  // твой же valueOf
        result[key] = value?.let { AttributeValue.Text(it) } ?: AttributeValue.Empty
    }
    if (request.includeUnmapped) {
        val mapped = request.names.keys + listOfNotNull(
            (sources.designation as? IdentitySource.Attribute)?.name,
            (sources.name as? IdentitySource.Attribute)?.name,
        )
        raw.forEach { a ->
            val name = a.name?.trim() ?: return@forEach
            val value = a.value?.trim().orEmpty()
            if (name !in mapped && value.isNotEmpty()) {
                result[AttributeKey.Unmapped(name)] = AttributeValue.Text(value)
            }
        }
    }
    return result
}