package dev.reportgenerator.domain

sealed interface Relation {
    data object Composition : Relation

    data object Document : Relation

    data class Unmapped(val pdmName: String) : Relation
}
