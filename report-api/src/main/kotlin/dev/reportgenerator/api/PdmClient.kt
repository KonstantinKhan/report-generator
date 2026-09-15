package dev.reportgenerator.api

data class ItemDto(
    val designation: String,
    val name: String,
    val kind: String,
    val quantity: Int
)

data class SpecificationDto(
    val items: List<ItemDto>
)

interface PdmClient {
    fun fetchSpecification(documentId: String): SpecificationDto
}

class InMemoryPdmClient(private val data: SpecificationDto) : PdmClient {
    override fun fetchSpecification(documentId: String): SpecificationDto = data
}
