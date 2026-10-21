package dev.reportgenerator.loodsman.mapping

import dev.reportgenerator.domain.PdmNodeKind
import khan366kos.mapping.IdentitySource
import khan366kos.mapping.IdentitySources

fun MappingConfig.identitySourcesOf(kind: PdmNodeKind): IdentitySources {
    val override = Registries.itemKinds.idOf(kind)?.let { identity.byKind[it] }
    val designation = override?.designation ?: identity.designation
    val name = override?.name ?: identity.name
    return IdentitySources(
        designation = designation.toIdentitySource(),
        name = name.toIdentitySource(),
    )
}

private fun FieldSource.toIdentitySource(): IdentitySource = when (this) {
    is FieldSource.Prop -> IdentitySource.Prop(field)          // enum общий — конвертации нет
    is FieldSource.Attribute -> IdentitySource.Attribute(name)
}