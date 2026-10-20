package dev.reportgenerator.domain

sealed interface Quantity {
    data class Exact(val amount: Double) : Quantity

    data class Range(val min: Double, val max: Double) : Quantity

    data object Missing : Quantity

    data object NotLoaded : Quantity
}

fun Quantity.orElse(default: String): String = when (this) {
    is Quantity.Exact -> amount.toString()
    is Quantity.Range -> "$min–$max"
    is Quantity.Missing -> default
    is Quantity.NotLoaded -> throw IllegalStateException(
        "Quantity is not loaded: the value was not requested from the PDM client"
    )
}
