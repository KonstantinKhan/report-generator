package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.HorizontalLink
import dev.reportgenerator.domain.HorizontalLinkType
import dev.reportgenerator.domain.InverseLink
import dev.reportgenerator.domain.InverseLinkType
import dev.reportgenerator.domain.PdmClient
import dev.reportgenerator.domain.PdmDatabase
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.directLinkType
import dev.reportgenerator.domain.horizontalLinkType
import dev.reportgenerator.domain.inverseLinkType
import dev.reportgenerator.domain.pdmType
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import io.ktor.client.HttpClient

class LoodsmanKtorPdmClient(
    private val config: LoodsmanConfig,
    private val httpClient: HttpClient = loodsmanHttpClient()
) : PdmClient {

    private val baseUrl = config.baseUrl.trimEnd('/')

    private val typeMeta = SuspendLazy { sessions.withSession { metaData.typeList(it) } }
    private val linkMeta = SuspendLazy { sessions.withSession { metaData.linkList(it) } }

    suspend fun pdmDatabases(): List<PdmDatabase> = auth.databases().map { db -> db.toDomain() }

    suspend fun userId(dbName: String, username: String, password: String) =
        auth.login(dbName, username, password).userId

    suspend fun isAdmin(): Boolean = sessions.withSession { auth.isAdmin(it) }

    override suspend fun getObject(objectId: Int): PdmObjectResult {
        val result = sessions.withSession { objectInfo.pdmObject(it, objectId) }
        val attributes = when (result) {
            is PropObjectResult.Found -> objectAttributes(objectId)
            is PropObjectResult.NotFound -> emptyList()
        }
        return result.toDomain(keyAttributeByType(), attributes)
    }

    internal suspend fun objectAttributes(versionId: Int): List<ObjectAttributeOutputDto> =
        sessions.withSession { objectInfo.infoAboutVersion(it, versionId) }

    suspend fun keyAttributeByType(): Map<PdmType, String> = typeMeta.get()
        .mapNotNull { dto ->
            val type = dto.typeName?.let(::pdmType) ?: return@mapNotNull null
            val attr = dto.attrName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            type to attr
        }
        .toMap()

    suspend fun pdmTypeById(): Map<Int, PdmType> = typeMeta.get()
        .mapNotNull { dto ->
            val type = dto.typeName?.let(::pdmType) ?: return@mapNotNull null
            dto.id to type
        }
        .toMap()

    suspend fun directLinkTypeById(): Map<Int, DirectLinkType> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val type = dto.name?.let(::directLinkType) ?: return@mapNotNull null
            dto.id to type
        }
        .toMap()

    suspend fun directLinkTypeByDomain(): Map<DirectLinkType, String> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val name = dto.name ?: return@mapNotNull null
            val type = directLinkType(name)
            type to name
        }
        .toMap()

    suspend fun inverseLinkTypeById(): Map<Int, InverseLinkType> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val name = dto.inverseName ?: return@mapNotNull null
            val type = inverseLinkType(name)
            dto.id to type
        }
        .toMap()

    suspend fun inverseLinkTypeByDomain(): Map<InverseLinkType, String> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val name = dto.inverseName ?: return@mapNotNull null
            val type = inverseLinkType(name)
            type to name
        }
        .toMap()

    suspend fun horizontalLinkTypeById(): Map<Int, HorizontalLinkType> = linkMeta.get()
        .filter { it.type == 1 }
        .mapNotNull { dto ->
            val name = dto.name ?: return@mapNotNull null
            val type = horizontalLinkType(name)
            dto.id to type
        }
        .toMap()

    suspend fun horizontalLinkTypeByDomain(): Map<HorizontalLinkType, String> = linkMeta.get()
        .filter { it.type == 1 }
        .mapNotNull { dto ->
            val name = dto.name ?: return@mapNotNull null
            val type = horizontalLinkType(name)
            type to name
        }
        .toMap()

    override suspend fun getDirectLinks(objectId: Int): List<DirectLink> {
        val typeById = directLinkTypeById()
        val verticalLinkTypeIds = typeById.keys.toList()
        if (verticalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = sessions.withSession { sessionId ->
            objectInfo.linkedObjects(sessionId, listOf(objectId), verticalLinkTypeIds, inverse = false)
        }

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: DirectLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toDirectLink(type)
        }
    }

    override suspend fun getInverseLinks(objectId: Int): List<InverseLink> {
        val typeById = inverseLinkTypeById()
        val verticalLinkTypeIds = typeById.keys.toList()
        if (verticalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = sessions.withSession { sessionId ->
            objectInfo.linkedObjects(sessionId, listOf(objectId), verticalLinkTypeIds, inverse = true)
        }

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: InverseLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toInverseLink(type)
        }
    }

    override suspend fun getHorizontalLinks(objectId: Int): List<HorizontalLink> {
        val typeById = horizontalLinkTypeById()
        val horizontalLinkTypeIds = typeById.keys.toList()
        if (horizontalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = sessions.withSession { sessionId ->
            objectInfo.linkedObjects(sessionId, listOf(objectId), horizontalLinkTypeIds, inverse = false)
        }

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: HorizontalLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toHorizontalLink(type)
        }
    }

    suspend fun logout() = sessions.close()

    private val auth: Auth = Auth(httpClient, baseUrl)
    private val objectInfo: ObjectInfo = ObjectInfo(httpClient, baseUrl)
    private val metaData: MetaData = MetaData(httpClient, baseUrl)
    private val sessions = SessionHolder(auth, config)
}
