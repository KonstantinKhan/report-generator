package dev.reportgenerator.domain

sealed interface LinkAttributeKey {
    data object Note : LinkAttributeKey

    data class Unmapped(val pdmName: String) : LinkAttributeKey
}
