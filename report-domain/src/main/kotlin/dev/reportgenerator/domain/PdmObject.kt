package dev.reportgenerator.domain

data class PdmObject(
    val id: Int,
    val designation: Designation,
    val name: Name,
    val type: PdmType,
    val state: String,
    val version: String,
    val position: Field<Int>
)
