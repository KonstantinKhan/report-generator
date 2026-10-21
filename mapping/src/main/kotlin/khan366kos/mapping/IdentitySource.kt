package khan366kos.mapping

import io.github.konstantinkhan.loodsman.PropField

sealed interface IdentitySource {
    data class Prop(val field: PropField) : IdentitySource
    data class Attribute(val name: String) : IdentitySource
}

data class IdentitySources(
    val designation: IdentitySource,
    val name: IdentitySource,
)

fun IdentitySources.needsAttributes(): Boolean =
    designation is IdentitySource.Attribute || name is IdentitySource.Attribute