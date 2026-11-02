package io.github.konstantinkhan.loodsman.dto

import kotlinx.serialization.Serializable

/**
 * Одна строка варианта группы замен: член варианта — связь состава [idLink] дочернего объекта [idVersion].
 * [groupChangeType]: 0 — потребитель, 1 — производство, 2 — допускаемая замена.
 */
@Serializable
data class V3_GetInfoAboutVersionMode20OutputDto(
    val idGroupChange: Int,
    val groupChangeName: String? = null,
    val groupChangeType: Int,
    val groupInCurrentConf: Int,
    val idVariant: Int,
    val variantName: String? = null,
    /** 1 — базовый вариант. */
    val basic: Int,
    val variantInCurrentConf: Int,
    val idLink: Int,
    val idParent: Int,
    val linkType: String? = null,
    val idVersion: Int,
    val idType: Int,
    val idState: Int,
    val product: String? = null,
    val version: String? = null,
    val idLock: Int,
    val accessLevel: Int,
    val label: Int,
    val labelName: String? = null
)
