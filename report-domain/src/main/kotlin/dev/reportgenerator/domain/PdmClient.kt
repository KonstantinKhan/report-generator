package dev.reportgenerator.domain

interface PdmClient {
    fun fetchSpecification(documentId: String): SpecificationData
}
