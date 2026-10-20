package dev.reportgenerator.domain

interface PdmClient {
    suspend fun getObject(objectId: Int): PdmObjectResult
    suspend fun getDirectLinks(objectId: Int): List<DirectLink>
    suspend fun getInverseLinks(objectId: Int): List<InverseLink>
    suspend fun getHorizontalLinks(objectId: Int): List<HorizontalLink>
}
