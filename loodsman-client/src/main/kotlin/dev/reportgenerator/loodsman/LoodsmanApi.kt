package dev.reportgenerator.loodsman

import dev.reportgenerator.loodsman.dto.DatabaseOutputDto
import dev.reportgenerator.loodsman.dto.GetLinkListOutputDto
import dev.reportgenerator.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import dev.reportgenerator.loodsman.dto.LinkAttributeOutputDto
import dev.reportgenerator.loodsman.dto.ObjectAttributeOutputDto
import dev.reportgenerator.loodsman.dto.PropObjectOutputDto
import dev.reportgenerator.loodsman.dto.TypeListOutputDto

interface LoodsmanApi {
    suspend fun databases(): List<DatabaseOutputDto>
    suspend fun isAdmin(): Boolean
    suspend fun logout()
    suspend fun typeList(): List<TypeListOutputDto>
    suspend fun linkList(): List<GetLinkListOutputDto>
    suspend fun propObjects(versionIds: List<Int>): List<PropObjectOutputDto>
    suspend fun infoAboutVersion(versionId: Int): List<ObjectAttributeOutputDto>
    suspend fun linkedObjects(
        objectIds: List<Int>,
        linkTypeIds: List<Int>,
        inverse: Boolean = false,
    ): List<GetLinkedObjectsForObjectsOutputDto_V4>
    suspend fun linkAttributes(linkId: Int): List<LinkAttributeOutputDto>
}
