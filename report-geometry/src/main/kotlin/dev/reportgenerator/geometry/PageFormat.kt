package dev.reportgenerator.geometry

data class PageFormat(
    val name: String,
    val width: Length,
    val height: Length
) {
    companion object {
        val A4 = PageFormat("A4", 210.mm, 297.mm)
        val A3 = PageFormat("A3", 297.mm, 420.mm)
    }
}
