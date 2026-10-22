package khan366kos.mapping

import dev.reportgenerator.domain.AttributeKey
import dev.reportgenerator.domain.Field
import dev.reportgenerator.domain.NodeKey
import dev.reportgenerator.domain.PdmNode
import dev.reportgenerator.domain.PdmNodeId
import dev.reportgenerator.domain.PdmNodeKind
import io.github.konstantinkhan.loodsman.PropField
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto

fun toDomain(
    props: PropObjectOutputDto,
    kind: PdmNodeKind,
    objectAttributes: List<ObjectAttributeOutputDto>,
    request: AttributeRequest,
    versionField: PropField,
    stateField: PropField,
): PdmNode {
    val keyAttribute = request.keyAttribute
    val key = keyAttribute?.let { name ->
        readAttribute(name, keyAttribute, props, objectAttributes)?.let { text ->
            NodeKey.Value(request.keyKey ?: AttributeKey.Unmapped(name), text)
        }
    } ?: NodeKey.Missing

    return PdmNode(
        id = PdmNodeId(props.idVersion),
        kind = kind,
        version = props.readProp(versionField)?.let { Field.Value(it) } ?: Field.Missing,
        state = props.readProp(stateField)?.let { Field.Value(it) } ?: Field.Missing,
        attributes = readAttributes(props, objectAttributes, request),
        key = key,
    )
}
