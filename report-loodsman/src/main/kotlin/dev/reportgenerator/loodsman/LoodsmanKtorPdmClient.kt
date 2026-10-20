package dev.reportgenerator.loodsman

import dev.reportgenerator.domain.DirectLink
import dev.reportgenerator.domain.DirectLinkType
import dev.reportgenerator.domain.HorizontalLink
import dev.reportgenerator.domain.HorizontalLinkType
import dev.reportgenerator.domain.InverseLink
import dev.reportgenerator.domain.InverseLinkType
import dev.reportgenerator.domain.PdmClient
import dev.reportgenerator.domain.PdmObjectResult
import dev.reportgenerator.domain.PdmType
import dev.reportgenerator.domain.directLinkType
import dev.reportgenerator.domain.horizontalLinkType
import dev.reportgenerator.domain.inverseLinkType
import dev.reportgenerator.domain.pdmType
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.LoodsmanApi

class LoodsmanKtorPdmClient(private val api: LoodsmanApi) : PdmClient {

    private val typeMeta = SuspendLazy { api.metaData.typeList() }
    private val linkMeta = SuspendLazy { api.metaData.linkList() }

    override suspend fun getObject(objectId: Int): PdmObjectResult {
        val result = api.objectInfo.propObjects(listOf(objectId)).firstOrNull()
            ?.let { PropObjectResult.Found(it) }
            ?: PropObjectResult.NotFound(objectId)
        val attributes = when (result) {
            is PropObjectResult.Found -> objectAttributes(objectId)
            is PropObjectResult.NotFound -> emptyList()
        }
        return result.toDomain(keyAttributeByType(), attributes)
    }

    private suspend fun objectAttributes(versionId: Int): List<ObjectAttributeOutputDto> =
        api.objectInfo.infoAboutVersion(versionId)

    private suspend fun keyAttributeByType(): Map<PdmType, String> = typeMeta.get()
        .mapNotNull { dto ->
            val type = dto.typeName?.let(::pdmType) ?: return@mapNotNull null
            val attr = dto.attrName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            type to attr
        }
        .toMap()

    private suspend fun directLinkTypeById(): Map<Int, DirectLinkType> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val type = dto.name?.let(::directLinkType) ?: return@mapNotNull null
            dto.id to type
        }
        .toMap()

    private suspend fun inverseLinkTypeById(): Map<Int, InverseLinkType> = linkMeta.get()
        .filter { it.type == 0 }
        .mapNotNull { dto ->
            val name = dto.inverseName ?: return@mapNotNull null
            val type = inverseLinkType(name)
            dto.id to type
        }
        .toMap()

    private suspend fun horizontalLinkTypeById(): Map<Int, HorizontalLinkType> = linkMeta.get()
        .filter { it.type == 1 }
        .mapNotNull { dto ->
            val name = dto.name ?: return@mapNotNull null
            val type = horizontalLinkType(name)
            dto.id to type
        }
        .toMap()

    override suspend fun getDirectLinks(objectId: Int): List<DirectLink> {
        val typeById = directLinkTypeById()
        val verticalLinkTypeIds = typeById.keys.toList()
        if (verticalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = api.objectInfo.linkedObjects(listOf(objectId), verticalLinkTypeIds, inverse = false)

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: DirectLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toDirectLink(type)
        }
    }

    override suspend fun getInverseLinks(objectId: Int): List<InverseLink> {
        val typeById = inverseLinkTypeById()
        val verticalLinkTypeIds = typeById.keys.toList()
        if (verticalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = api.objectInfo.linkedObjects(listOf(objectId), verticalLinkTypeIds, inverse = true)

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: InverseLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toInverseLink(type)
        }
    }

    override suspend fun getHorizontalLinks(objectId: Int): List<HorizontalLink> {
        val typeById = horizontalLinkTypeById()
        val horizontalLinkTypeIds = typeById.keys.toList()
        if (horizontalLinkTypeIds.isEmpty()) return emptyList()

        val dtos = api.objectInfo.linkedObjects(listOf(objectId), horizontalLinkTypeIds, inverse = false)

        return dtos.map { dto ->
            val type = typeById[dto.idLinkType] ?: HorizontalLinkType.Unused(dto.linkTypeName.orEmpty())
            dto.toHorizontalLink(type)
        }
    }

}
