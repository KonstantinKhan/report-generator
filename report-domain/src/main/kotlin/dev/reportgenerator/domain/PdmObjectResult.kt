package dev.reportgenerator.domain

sealed class PdmObjectResult {
    data class Found(val value: PdmObject) : PdmObjectResult()
    data class NotFound(val id: Int) : PdmObjectResult()
}
