package dev.reportgenerator.ir

data class TextStyle(
    val fontFamily: String,
    val fontSizeMm: Double,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)

data class BorderStyle(
    val widthPt: Double
)

data class TableStyle(
    val border: BorderStyle
)

// Referenced by name (TextStyle.fontFamily is a plain String, not a sealed type) wherever a
// fontResolver maps family -> actual font bytes, e.g. report-cli's Main.kt. Both sides importing
// the same constant turns a typo into a compile error instead of a silent fallback to the wrong
// font at runtime.
object FontFamilies {
    const val GOST_TYPE_A = "GOST Type A"
    const val GOST_TYPE_B = "GOST Type B"
}

object Styles {
    val mainText = TextStyle(fontFamily = FontFamilies.GOST_TYPE_A, fontSizeMm = 3.5)
    val tableText = TextStyle(fontFamily = FontFamilies.GOST_TYPE_A, fontSizeMm = 3.5)
    val heading = TextStyle(fontFamily = FontFamilies.GOST_TYPE_A, fontSizeMm = 5.0, bold = true)
    val designation = TextStyle(fontFamily = FontFamilies.GOST_TYPE_A, fontSizeMm = 2.5)
    val tableHeader = TextStyle(fontFamily = FontFamilies.GOST_TYPE_B, fontSizeMm = 3.5)
    val groupHeader = TextStyle(fontFamily = FontFamilies.GOST_TYPE_B, fontSizeMm = 3.5, italic = true, underline = true)
    // Total rows of a flow table (subtotals, grand total): Type B like the header, bold so a total reads apart from
    // the data lines (bold is a style flag only, there is no bold font loaded yet, see fonts-and-licensing.md).
    val totalText = TextStyle(fontFamily = FontFamilies.GOST_TYPE_B, fontSizeMm = 3.5, bold = true)
    val frameText = TextStyle(fontFamily = FontFamilies.GOST_TYPE_B, fontSizeMm = 3.5)
    val frameTextLarge = TextStyle(fontFamily = FontFamilies.GOST_TYPE_B, fontSizeMm = 7.0)
    val tableBorder = BorderStyle(widthPt = 2.0)

    // GOST 2.303: thin line = S/3..S/2 of the thick line (S == tableBorder, ~0.706mm here),
    // i.e. 0.235-0.353mm. 0.7pt ~= 0.247mm, inside that range.
    val tableBorderThin = BorderStyle(widthPt = 0.7)

    // Style names usable as `style:` in YAML (flow tables), see FlowTables.
    val named: Map<String, TextStyle> = linkedMapOf(
        "mainText" to mainText,
        "tableText" to tableText,
        "heading" to heading,
        "designation" to designation,
        "tableHeader" to tableHeader,
        "groupHeader" to groupHeader,
        "totalText" to totalText,
        "frameText" to frameText,
        "frameTextLarge" to frameTextLarge
    )
}
