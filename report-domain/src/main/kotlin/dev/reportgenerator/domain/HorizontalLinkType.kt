package dev.reportgenerator.domain

sealed interface HorizontalLinkType {
    data class Unused(val value: String) : HorizontalLinkType
}

fun horizontalLinkType(name: String?): HorizontalLinkType =
    name?.trim()?.takeIf { it.isNotEmpty() }?.let {
        HorizontalLinkType.Unused(it)
    } ?: throw IllegalArgumentException("horizontalLinkType cannot be null")
