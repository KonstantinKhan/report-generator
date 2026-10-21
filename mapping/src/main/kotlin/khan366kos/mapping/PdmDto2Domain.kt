package khan366kos.mapping

import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

fun toDomain(
    props: PropObjectOutputDto,
    attributes: List<ObjectAttributeOutputDto>,
    sourceOf: (PdmNodeKind) -> IdentitySources,
    kindOf: (String) -> PdmNodeKind
): PdmNode {
    val kind = kindOf(props.type ?: error("Loodsman object id=${props.idVersion}: type is null"))
    val identity = resolveIdentity(kind, props, attributes, sourceOf)
    return PdmNode(
        id = PdmNodeId(props.idVersion),
        kind = kind,
        designation = identity.designation,
        name = identity.name,
        version = props.version?.let { Field.Value(it) } ?: Field.Missing,
        state = props.state?.let { Field.Value(it) } ?: Field.Missing,
    )
}