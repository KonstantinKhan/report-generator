package dev.reportgenerator.ir

data class TextStyle(
    val fontFamily: String,
    val fontSizeMm: Double,
    val bold: Boolean = false,
    val italic: Boolean = false
)

data class BorderStyle(
    val widthPt: Double
)

data class TableStyle(
    val border: BorderStyle
)

object Styles {
    val mainText = TextStyle(fontFamily = "GOST Type A", fontSizeMm = 3.5)
    val tableText = TextStyle(fontFamily = "GOST Type A", fontSizeMm = 2.5)
    val heading = TextStyle(fontFamily = "GOST Type A", fontSizeMm = 5.0, bold = true)
    val designation = TextStyle(fontFamily = "GOST Type A", fontSizeMm = 2.5)
    val tableHeader = TextStyle(fontFamily = "GOST Type B", fontSizeMm = 2.5)
    val tableBorder = BorderStyle(widthPt = 2.0)
}
