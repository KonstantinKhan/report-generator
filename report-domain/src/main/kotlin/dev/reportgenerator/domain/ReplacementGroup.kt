package dev.reportgenerator.domain

/**
 * Группа замен (допускаемые замены) узла изделия.
 * [type]: потребитель, производство или допускаемая замена; каждый вариант группы — набор членов.
 * [inCurrentConfiguration] — входит в текущую конфигурацию сессии Loodsman.
 */
data class ReplacementGroup(
    val id: Int,
    val name: String?,
    val type: ReplacementType,
    val variants: List<ReplacementVariant>,
    val inCurrentConfiguration: Boolean,
)

enum class ReplacementType { Consumer, Production, Permissible }

/**
 * Вариант группы замен. [linkIds] — id связей состава членов варианта (совпадают с [CompositionUsage.linkId]
 * у дочерних usages узла). [basic] — базовый вариант группы.
 * [inCurrentConfiguration] — входит в текущую конфигурацию сессии Loodsman.
 */
data class ReplacementVariant(
    val id: Int,
    val name: String?,
    val basic: Boolean,
    val linkIds: List<Int>,
    val inCurrentConfiguration: Boolean,
)
