package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.LinkAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.ktor.client.request.parameter

internal class ObjectInfo(
    private val http: SessionClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/ObjectInfo"

    internal suspend fun infoAboutVersion(versionId: Int): List<ObjectAttributeOutputDto> =
        http.get("$blockBaseUrl/get-info-about-version-mode-3") {
            parameter("idVersion", versionId)
        }

    internal suspend fun pdmObjects(versionIds: List<Int>): List<PropObjectOutputDto> =
        http.get("$blockBaseUrl/get-prop-objects") {
            parameter("objectList", versionIds.joinToString(","))
            parameter("parameters", 0)
        }

    internal suspend fun linkedObjects(
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean
    ): List<GetLinkedObjectsForObjectsOutputDto_V4> =
        http.get("$blockBaseUrl/get-linked-objects-for-objects") {
            parameter("objectsIds", objectIds.joinToString(","))
            parameter("linksTypesIds", linkTypeIds.joinToString(","))
            parameter("inverse", inverse)
        }

    internal suspend fun linkAttributes(linkId: Int): List<LinkAttributeOutputDto> =
        http.get("$blockBaseUrl/get-link-attributes") {
            parameter("linkId", linkId)
        }
}
