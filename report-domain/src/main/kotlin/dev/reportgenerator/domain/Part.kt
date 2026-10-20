package dev.reportgenerator.domain

data class Part(
    override val id: Int,
    override val designation: Designation,
    override val name: Name,
    override val state: String,
    override val version: String
) : Item
