package dev.reportgenerator.geometry

data class Point(val x: Length, val y: Length)

data class Size(val width: Length, val height: Length)

data class Rect(val x: Length, val y: Length, val width: Length, val height: Length) {
    val left: Length get() = x
    val top: Length get() = y
    val right: Length get() = x + width
    val bottom: Length get() = y + height
}

data class Insets(val top: Length, val right: Length, val bottom: Length, val left: Length) {
    companion object {
        val ZERO: Insets = Insets(Length.ZERO, Length.ZERO, Length.ZERO, Length.ZERO)
    }
}
