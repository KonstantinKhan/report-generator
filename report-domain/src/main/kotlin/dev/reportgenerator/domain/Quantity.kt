package dev.reportgenerator.domain

sealed interface Quantity {
    data class Exact(val amount: Double) : Quantity

    data class Range(val min: Double, val max: Double) : Quantity

    data object Missing : Quantity

    data object NotLoaded : Quantity
}
