package khan366kos.mapping

import io.github.konstantinkhan.loodsman.PropField
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

/**
 * Единое чтение атрибута объекта. Если [name] равно ключевому атрибуту типа ([keyAttribute]),
 * значение лежит в свойстве `product`, иначе — в списке атрибутов объекта.
 * Пустое значение (после trim) считается отсутствующим.
 */
fun readAttribute(
    name: String,
    keyAttribute: String?,
    props: PropObjectOutputDto,
    attributes: List<ObjectAttributeOutputDto>,
): String? {
    val target = name.trim()
    val raw = if (keyAttribute != null && target == keyAttribute.trim()) {
        props.product
    } else {
        attributes.firstOrNull { it.name?.trim() == target }?.value
    }
    return raw?.trim()?.takeIf { it.isNotEmpty() }
}

fun PropObjectOutputDto.readProp(field: PropField): String? = when (field) {
    PropField.PRODUCT -> product
    PropField.VERSION -> version
    PropField.STATE -> state
    PropField.TYPE -> type
}?.trim()?.takeIf { it.isNotEmpty() }
