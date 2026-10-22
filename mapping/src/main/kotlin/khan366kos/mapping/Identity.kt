package khan366kos.mapping

import dev.reportgenerator.domain.Designation
import dev.reportgenerator.domain.Name
import io.github.konstantinkhan.loodsman.PropField
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

data class Identity(
    val designation: Designation,
    val name: Name,
)

fun resolveIdentity(
    sources: IdentitySources,
    props: PropObjectOutputDto,
    attributes: List<ObjectAttributeOutputDto>,
): Identity {
    return Identity(
        designation = sources.designation.readFrom(props, attributes).toDesignation(),
        name = sources.name.readFrom(props, attributes).toName()
    )
}

private fun IdentitySource.readFrom(
    props: PropObjectOutputDto,
    attributes: List<ObjectAttributeOutputDto>,
): String? = when (this) {
    is IdentitySource.Prop -> props.read(field)
    is IdentitySource.Attribute -> attributes.valueOf(name)
}

private fun PropObjectOutputDto.read(field: PropField): String? = when (field) {
    PropField.PRODUCT -> product
    PropField.VERSION -> version
    PropField.STATE -> state
    PropField.TYPE -> type
}

fun List<ObjectAttributeOutputDto>.valueOf(name: String): String? =
    firstOrNull { it.name?.trim() == name }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun String?.toDesignation(): Designation =
    this?.let(Designation::Value) ?: Designation.Missing

private fun String?.toName(): Name =
    this?.let(Name::Value) ?: Name.Missing