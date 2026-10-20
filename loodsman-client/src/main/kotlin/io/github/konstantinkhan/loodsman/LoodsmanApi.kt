package io.github.konstantinkhan.loodsman

import io.github.konstantinkhan.loodsman.dto.DatabaseOutputDto
import io.github.konstantinkhan.loodsman.dto.GetLinkListOutputDto
import io.github.konstantinkhan.loodsman.dto.GetLinkedObjectsForObjectsOutputDto_V4
import io.github.konstantinkhan.loodsman.dto.LinkAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.ObjectAttributeOutputDto
import io.github.konstantinkhan.loodsman.dto.PropObjectOutputDto
import io.github.konstantinkhan.loodsman.dto.TypeListOutputDto

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
