package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.LinkAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.github.konstantinkhan.loodsman.dto.V3_GetInfoAboutVersionMode20OutputDto
import io.ktor.client.request.parameter

class ObjectInfoApi internal constructor(
    private val http: SessionClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/ObjectInfo"

    suspend fun infoAboutVersion(versionId: Int): List<ObjectAttributeOutputDto> =
        http.get("$blockBaseUrl/get-info-about-version-mode-3") {
            parameter("idVersion", versionId)
        }

    suspend fun propObjects(versionIds: List<Int>): List<PropObjectOutputDto> =
        http.get("$blockBaseUrl/get-prop-objects") {
            parameter("objectList", versionIds.joinToString(","))
            parameter("parameters", 0)
        }

    suspend fun propObject(versionId: Int): PropObjectOutputDto =
        propObjects(listOf(versionId)).first()

    suspend fun linkedObjects(
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean = false,
    ): List<GetLinkedObjectsForObjectsOutputDto_V4> =
        http.get("$blockBaseUrl/get-linked-objects-for-objects") {
            parameter("objectsIds", objectIds.joinToString(","))
            parameter("linksTypesIds", linkTypeIds.joinToString(","))
            parameter("inverse", inverse)
        }

    suspend fun linkAttributes(linkId: Int): List<LinkAttributeOutputDto> =
        http.get("$blockBaseUrl/get-link-attributes") {
            parameter("linkId", linkId)
        }

    /** Группы замен объекта: по одной строке на каждого члена варианта. Пустой список — замен нет. */
    suspend fun replacementVariants(versionId: Int): List<V3_GetInfoAboutVersionMode20OutputDto> =
        http.get("$blockBaseUrl/get-info-about-version-mode-20") {
            parameter("idVersion", versionId)
        }
}
