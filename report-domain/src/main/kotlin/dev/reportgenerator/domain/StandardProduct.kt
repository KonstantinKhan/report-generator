package dev.reportgenerator.domain

data class StandardProduct(
    override val id: Int,
    override val designation: Designation,
    override val name: Name,
    override val state: String,
    override val version: String
) : Item
