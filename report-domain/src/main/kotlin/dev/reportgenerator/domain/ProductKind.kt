package dev.reportgenerator.domain

sealed interface ProductKind {
    data object Assembly : ProductKind

    data object Part : ProductKind

    data object Standard : ProductKind

    data object Other : ProductKind

    data object Material : ProductKind

    data object Document : ProductKind

    data object Complex : ProductKind

    data object Kit : ProductKind

    data class Unmapped(val pdmName: String) : ProductKind
}
