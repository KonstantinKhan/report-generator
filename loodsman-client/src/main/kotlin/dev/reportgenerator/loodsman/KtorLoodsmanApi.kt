package dev.reportgenerator.loodsman

import dev.reportgenerator.loodsman.dto.DatabaseOutputDto
import dev.reportgenerator.loodsman.dto.GetLinkListOutputDto
import dev.reportgenerator.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import dev.reportgenerator.loodsman.dto.LinkAttributeOutputDto
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import dev.reportgenerator.loodsman.dto.PropObjectOutputDto
import dev.reportgenerator.loodsman.dto.TypeListOutputDto
import io.ktor.client.HttpClient

class KtorLoodsmanApi(
    config: LoodsmanConfig,
    httpClient: HttpClient,
) : LoodsmanApi {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val auth = Auth(httpClient, baseUrl)
    private val objectInfo = ObjectInfo(httpClient, baseUrl)
    private val metaData = MetaData(httpClient, baseUrl)
    private val sessions = SessionHolder(auth, config)

    override suspend fun databases(): List<DatabaseOutputDto> = auth.databases()

    override suspend fun isAdmin(): Boolean = sessions.withSession { auth.isAdmin(it) }

    override suspend fun logout() = sessions.close()

    override suspend fun typeList(): List<TypeListOutputDto> = sessions.withSession { metaData.typeList(it) }

    override suspend fun linkList(): List<GetLinkListOutputDto> = sessions.withSession { metaData.linkList(it) }

    override suspend fun propObjects(versionIds: List<Int>): List<PropObjectOutputDto> =
        sessions.withSession { objectInfo.pdmObjects(it, versionIds) }

    override suspend fun infoAboutVersion(versionId: Int): List<ObjectAttributeOutputDto> =
        sessions.withSession { objectInfo.infoAboutVersion(it, versionId) }

    override suspend fun linkedObjects(
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean,
    ): List<GetLinkedObjectsForObjectsOutputDto_V4> =
        sessions.withSession { objectInfo.linkedObjects(it, objectIds, linkTypeIds, inverse) }

    override suspend fun linkAttributes(linkId: Int): List<LinkAttributeOutputDto> =
        sessions.withSession { objectInfo.linkAttributes(it, linkId) }
}
