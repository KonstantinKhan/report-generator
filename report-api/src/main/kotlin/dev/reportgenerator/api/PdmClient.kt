package dev.reportgenerator.api

data class ItemDto(
    val designation: String?,
    val name: String,
    val kind: String,
    val quantity: Double,
    val unit: String? = null
)

data class SpecificationDto(
    val documentDesignation: String,
    val documentName: String,
    val items: List<ItemDto>
)

interface PdmClient {
    fun fetchSpecification(documentId: String): SpecificationDto
}

class InMemoryPdmClient(private val data: SpecificationDto) : PdmClient {
    override fun fetchSpecification(documentId: String): SpecificationDto = data
}
