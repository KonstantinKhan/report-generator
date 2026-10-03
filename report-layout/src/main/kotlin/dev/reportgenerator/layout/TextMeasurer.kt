package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont

interface TextMeasurer {
    // breakLongWords: a single word wider than maxWidth is split by characters instead of being
    // left to overflow. Off by default — frame/header cells keep their historical "overflow the
    // padding but stay in the cell" behavior (e.g. "Изм" in a narrow stamp cell); only table body
    // cells (splitRowIntoPhysicalRows) opt in.
    fun measure(text: String, style: TextStyle, maxWidth: Length, breakLongWords: Boolean = false): TextMeasurement
}

data class TextMeasurement(
    val width: Length,
    val height: Length,
    val lineCount: Int,
    val lines: List<String>
)

class PdfBoxTextMeasurer(
    private val fontRegistry: FontRegistry,
    private val fontResolver: (TextStyle) -> FontRef
) : TextMeasurer {

    private val measurementDocument = PDDocument()

    override fun measure(text: String, style: TextStyle, maxWidth: Length, breakLongWords: Boolean): TextMeasurement {
        if (text.isEmpty()) return TextMeasurement(Length.ZERO, Length.ZERO, 0, emptyList())

        val font = fontRegistry.loadInto(measurementDocument, fontResolver(style))
        val sizePt = style.fontSizeMm / PT_TO_MM
        val lines = wrapIntoLines(text, font, sizePt, maxWidth, breakLongWords)
        val width = lines.maxOf { lineWidth(it, font, sizePt) }
        val lineHeight = Length.ofMillimeters(style.fontSizeMm * LINE_HEIGHT_FACTOR)

        return TextMeasurement(width, lineHeight * lines.size, lines.size, lines)
    }

    private fun lineWidth(line: String, font: PDFont, sizePt: Double): Length {
        val widthInPt = font.getStringWidth(line) / 1000.0 * sizePt
        return Length.ofMillimeters(widthInPt * PT_TO_MM)
    }

    // Greedy word wrap. Empty "words" from repeated/edge spaces are skipped. With breakLongWords a
    // single word wider than maxWidth is hard-broken by characters (no hyphen) so no line exceeds
    // maxWidth — except a lone character wider than maxWidth, which must still go somewhere.
    private fun wrapIntoLines(text: String, font: PDFont, sizePt: Double, maxWidth: Length, breakLongWords: Boolean): List<String> {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        var current = ""

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (lineWidth(candidate, font, sizePt) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) lines += current
            // Fresh line for this word; the tail of a hard-broken word stays open for the next word.
            var rest = word
            while (breakLongWords && rest.length > 1 && lineWidth(rest, font, sizePt) > maxWidth) {
                val fit = (1 until rest.length).lastOrNull { lineWidth(rest.substring(0, it), font, sizePt) <= maxWidth } ?: 1
                lines += rest.substring(0, fit)
                rest = rest.substring(fit)
            }
            current = rest
        }
        if (current.isNotEmpty()) lines += current

        return lines.ifEmpty { listOf("") }
    }

    companion object {
        private const val PT_TO_MM = 25.4 / 72.0
        private const val LINE_HEIGHT_FACTOR = 1.2
    }
}
