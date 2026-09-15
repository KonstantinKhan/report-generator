package dev.reportgenerator.ir

data class LayoutConstraints(
    val keepTogether: Boolean = false,
    val keepWithNext: Boolean = false,
    val avoidBreakBefore: Boolean = false,
    val avoidBreakAfter: Boolean = false,
    val allowBreak: Boolean = true
) {
    companion object {
        val Default = LayoutConstraints()
    }
}
