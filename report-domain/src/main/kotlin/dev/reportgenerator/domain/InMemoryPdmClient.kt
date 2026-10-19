package dev.reportgenerator.domain

class InMemoryPdmClient(
    private val data: SpecificationData,
    private val objects: Map<Int, PdmObject> = emptyMap(),
    private val directLinks: Map<Int, List<DirectLink>> = emptyMap()
) : PdmClient {
    override fun fetchSpecification(documentId: String): SpecificationData = data
    override suspend fun getObject(objectId: Int): PdmObjectResult =
        objects[objectId]?.let { PdmObjectResult.Found(it) } ?: PdmObjectResult.NotFound(objectId)
    override suspend fun getDirectLinks(objectId: Int): List<DirectLink> = directLinks[objectId] ?: emptyList()
    override suspend fun getInverseLinks(objectId: Int): List<InverseLink> = emptyList()
    override suspend fun getHorizontalLinks(objectId: Int): List<HorizontalLink> = emptyList()
}
