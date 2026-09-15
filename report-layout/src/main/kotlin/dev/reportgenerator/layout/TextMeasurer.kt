package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont

interface TextMeasurer {
    fun measure(text: String, style: TextStyle, maxWidth: Length): TextMeasurement
}

data class TextMeasurement(
    val width: Length,
    val height: Length,
    val lineCount: Int
)

class PdfBoxTextMeasurer(
    private val fontRegistry: FontRegistry,
    private val fontResolver: (TextStyle) -> FontRef
) : TextMeasurer {

    private val measurementDocument = PDDocument()

    override fun measure(text: String, style: TextStyle, maxWidth: Length): TextMeasurement {
        if (text.isEmpty()) return TextMeasurement(Length.ZERO, Length.ZERO, 0)

        val font = fontRegistry.loadInto(measurementDocument, fontResolver(style))
        val lines = wrapIntoLines(text, font, style.fontSizePt, maxWidth)
        val width = lines.maxOf { lineWidth(it, font, style.fontSizePt) }
        val lineHeight = Length.ofMillimeters(style.fontSizePt * PT_TO_MM * LINE_HEIGHT_FACTOR)

        return TextMeasurement(width, lineHeight * lines.size, lines.size)
    }

    private fun lineWidth(line: String, font: PDFont, sizePt: Double): Length {
        val widthInPt = font.getStringWidth(line) / 1000.0 * sizePt
        return Length.ofMillimeters(widthInPt * PT_TO_MM)
    }

    private fun wrapIntoLines(text: String, font: PDFont, sizePt: Double, maxWidth: Length): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || lineWidth(candidate, font, sizePt) <= maxWidth) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current

        return lines.ifEmpty { listOf("") }
    }

    companion object {
        private const val PT_TO_MM = 25.4 / 72.0
        private const val LINE_HEIGHT_FACTOR = 1.2
    }
}
