package dev.reportgenerator.domain

sealed interface Item {
    val id: Int
    val designation: Designation
    val name: Name
    val state: String
    val version: String
}
