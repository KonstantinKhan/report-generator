package dev.reportgenerator.domain

/**
 * Запрос структуры изделия.
 *
 * [depth] — общий предел обхода: дети по любой связи появляются на уровнях 1..depth.
 * [relationDepth] — отдельный предел по связи: дети по связи R появляются на уровнях
 * 1..min(depth, relationDepth[R]); связи без записи в карте предел равен [depth].
 * Значение 0 и ключи вне [relations] запрещены (проверяется в клиенте).
 *
 * [replacements] — грузить группы замен ([PdmNode.replacements]) для узлов на уровнях 0..[replacementDepth],
 * где корень — уровень 0. При `false` замены не читаются. [replacementDepth] без [replacements] запрещён
 * и не может быть отрицательным (проверяется в клиенте).
 */
data class StructureQuery(
    val rootId: PdmNodeId,
    val depth: Int = Int.MAX_VALUE,
    val relations: Set<Relation> = setOf(Relation.Composition),
    val relationDepth: Map<Relation, Int> = emptyMap(),
    val attributes: Set<AttributeKey> = emptySet(),
    val linkAttributes: Set<LinkAttributeKey> = emptySet(),
    val includeUnmappedAttributes: Boolean = false,
    val replacements: Boolean = false,
    val replacementDepth: Int = 0,
)
