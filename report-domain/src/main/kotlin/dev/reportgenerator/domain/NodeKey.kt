package dev.reportgenerator.domain

sealed interface NodeKey {
    data class Value(val attribute: AttributeKey, val text: String) : NodeKey

    data object Missing : NodeKey

    data object NotLoaded : NodeKey
}
