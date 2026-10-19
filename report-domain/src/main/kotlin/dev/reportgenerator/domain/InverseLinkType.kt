package dev.reportgenerator.domain

sealed interface InverseLinkType {
    data object PartOf : InverseLinkType
    data class Unused(val value: String) : InverseLinkType
}

fun inverseLinkType(name: String?): InverseLinkType =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let {
        when (it) {
            "Входит в ..." -> InverseLinkType.PartOf
            else -> InverseLinkType.Unused(it)
        }
    } ?: throw IllegalArgumentException("inverseLinkType cannot be null")
