package dev.reportgenerator.domain

sealed interface LinkAttributeKey {
    data object Position : LinkAttributeKey

    data object Note : LinkAttributeKey

    data object Zone : LinkAttributeKey

    data object ApplicationMethod : LinkAttributeKey

    data class Unmapped(val pdmName: String) : LinkAttributeKey
}
