package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.PdmNodeKind
import dev.reportgenerator.domain.Relation
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import khan366kos.mapping.AttributeRequest

/**
 * Загруженный узел обхода. Для узла без вида [kind] и [request] не заданы.
 * [request] вычисляется один раз при загрузке узла.
 */
internal class LoadedNode(
    val prop: PropObjectOutputDto,
    val kind: PdmNodeKind?,
    val attributes: List<ObjectAttributeOutputDto>,
    val keyAttribute: String?,
    val request: AttributeRequest?,
)

/** Связь обхода: тип связи в доменной модели и DTO Loodsman. */
internal class Link(
    val relation: Relation,
    val dto: GetLinkedObjectsForObjectsOutputDto_V4,
    val linkId: Int = dto.idLink,
)

/** Значения атрибутов связи по имени Loodsman. */
internal data class LinkAttributesData(
    val attributes: Map<String, String>,
)

/** Состояние обхода дерева: загруженные узлы и связи по id родителя. */
internal class TraversalState(
    val nodes: MutableMap<Int, LoadedNode> = HashMap(),
    val links: MutableMap<Int, List<Link>> = HashMap(),
)
