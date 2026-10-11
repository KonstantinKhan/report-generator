package dev.reportgenerator.domain

class InMemoryPdmClient(private val data: SpecificationData) : PdmClient {
    override fun fetchSpecification(documentId: String): SpecificationData = data
}
