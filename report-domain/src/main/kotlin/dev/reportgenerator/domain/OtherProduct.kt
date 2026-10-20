package dev.reportgenerator.domain

data class OtherProduct(
    override val id: Int,
    override val designation: Designation,
    override val name: Name,
    override val state: String,
    override val version: String
) : Item
