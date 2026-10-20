package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.DatabaseOutputDto
import io.github.konstantinkhan.loodsman.dto.GetLinkListOutputDto
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.LinkAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.github.konstantinkhan.loodsman.dto.TypeListOutputDto
import io.ktor.client.HttpClient

class KtorLoodsmanApi(
    config: LoodsmanConfig,
    httpClient: HttpClient,
) : LoodsmanApi {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val auth = Auth(httpClient, baseUrl)
    private val sessions = SessionHolder(auth, config)
    private val http = SessionClient(httpClient, sessions)
    private val objectInfo = ObjectInfo(http, baseUrl)
    private val metaData = MetaData(http, baseUrl)

    override suspend fun databases(): List<DatabaseOutputDto> = auth.databases()

    override suspend fun isAdmin(): Boolean = sessions.withSession { auth.isAdmin(it) }

    override suspend fun logout() = sessions.close()

    override suspend fun typeList(): List<TypeListOutputDto> = metaData.typeList()

    override suspend fun linkList(): List<GetLinkListOutputDto> = metaData.linkList()

    override suspend fun propObjects(versionIds: List<Int>): List<PropObjectOutputDto> =
        objectInfo.pdmObjects(versionIds)

    override suspend fun infoAboutVersion(versionId: Int): List<ObjectAttributeOutputDto> =
        objectInfo.infoAboutVersion(versionId)

    override suspend fun linkedObjects(
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean,
    ): List<GetLinkedObjectsForObjectsOutputDto_V4> =
        objectInfo.linkedObjects(objectIds, linkTypeIds, inverse)

    override suspend fun linkAttributes(linkId: Int): List<LinkAttributeOutputDto> =
        objectInfo.linkAttributes(linkId)
}
