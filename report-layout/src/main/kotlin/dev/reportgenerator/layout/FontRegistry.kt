package dev.reportgenerator.layout

import dev.reportgenerator.layoutir.FontRef
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font

class FontRegistry {
    private val fontBytes = mutableMapOf<String, ByteArray>()

    fun register(id: String, ttfBytes: ByteArray): FontRef {
        fontBytes[id] = ttfBytes
        return FontRef(id)
    }

    fun bytesFor(ref: FontRef): ByteArray =
        fontBytes[ref.id] ?: error("Unknown font ref: ${ref.id}")

    fun loadInto(document: PDDocument, ref: FontRef): PDFont =
        PDType0Font.load(document, bytesFor(ref).inputStream())
}
