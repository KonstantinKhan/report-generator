package dev.reportgenerator.domain

sealed interface Name {
    @JvmInline
    value class Value(val text: String) : Name

    data object Missing : Name

    data object NotLoaded : Name
}

fun Name.orElse(default: String): String = when (this) {
    is Name.Value -> text
    is Name.Missing -> default
    is Name.NotLoaded -> throw IllegalStateException(
        "Name is not loaded: the value was not requested from the PDM client"
    )
}
