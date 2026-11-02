package khan366kos.mapping

import io.github.konstantinkhan.loodsman.PropField
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

/**
 * Единое чтение атрибута объекта. Если [name] равно ключевому атрибуту типа ([keyAttribute]),
 * значение лежит в свойстве `product`, иначе — в списке атрибутов объекта.
 * Имя и значение сравниваются и возвращаются как есть, без trim. Пустое значение считается отсутствующим.
 */
fun readAttribute(
    name: String,
    keyAttribute: String?,
    props: PropObjectOutputDto,
    attributes: List<ObjectAttributeOutputDto>,
): String? {
    val raw = if (keyAttribute != null && name == keyAttribute) {
        props.product
    } else {
        attributes.firstOrNull { it.name == name }?.value
    }
    return raw?.takeIf { it.isNotEmpty() }
}

fun PropObjectOutputDto.readProp(field: PropField): String? = when (field) {
    PropField.PRODUCT -> product
    PropField.VERSION -> version
    PropField.STATE -> state
    PropField.TYPE -> type
}?.takeIf { it.isNotEmpty() }
