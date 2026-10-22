package khan366kos.mapping

import dev.reportgenerator.domain.AttributeKey

data class AttributeRequest(
    val names: Map<String, AttributeKey>,   // имя в Лоцмане → доменный ключ
    val includeUnmapped: Boolean = false,
)
