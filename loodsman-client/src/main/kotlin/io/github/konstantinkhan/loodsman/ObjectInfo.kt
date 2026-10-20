package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.LinkAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter

internal class ObjectInfo(
    private val client: HttpClient,
    baseUrl: String
) {

    private val blockBaseUrl = "$baseUrl/api/v4/ObjectInfo"

    internal suspend fun infoAboutVersion(sessionId: String, versionId: Int): List<ObjectAttributeOutputDto> =
        client.get("$blockBaseUrl/get-info-about-version-mode-3") {
            header("web-loodsman-session", sessionId)
            parameter("idVersion", versionId)
        }.body()

    internal suspend fun pdmObjects(sessionId: String, versionIds: List<Int>): List<PropObjectOutputDto> =
        client.get("$blockBaseUrl/get-prop-objects") {
            header("web-loodsman-session", sessionId)
            parameter("objectList", versionIds.joinToString(","))
            parameter("parameters", 0)
        }.body()

    internal suspend fun linkedObjects(
        sessionId: String,
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean
    ): List<GetLinkedObjectsForObjectsOutputDto_V4> =
        client.get("$blockBaseUrl/get-linked-objects-for-objects") {
            header("web-loodsman-session", sessionId)
            parameter("objectsIds", objectIds.joinToString(","))
            parameter("linksTypesIds", linkTypeIds.joinToString(","))
            parameter("inverse", inverse)
        }.body()

    internal suspend fun linkAttributes(sessionId: String, linkId: Int): List<LinkAttributeOutputDto> =
        client.get("$blockBaseUrl/get-link-attributes") {
            header("web-loodsman-session", sessionId)
            parameter("linkId", linkId)
        }.body()
}
