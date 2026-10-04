package dev.reportgenerator.layout

import dev.reportgenerator.ir.FontFamilies
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef

// GOST Type A: real font not available/licensed — PT Sans Regular remains a stand-in.
// GOST Type B: real ASCON font (KOMPAS-3D), licensed for this use.
class DefaultFontRegistry private constructor(
    val registry: FontRegistry,
    private val regularRef: FontRef,
    private val gostARef: FontRef,
    private val gostAItalicRef: FontRef,
    private val gostBRef: FontRef,
    private val gostBItalicRef: FontRef,
    private val gostAURef: FontRef,
    private val gostBURef: FontRef
) {
    fun resolve(style: TextStyle): FontRef =
        when (style.fontFamily) {
            FontFamilies.GOST_TYPE_A -> gostARef
            FontFamilies.GOST_TYPE_A_ITALIC -> gostAItalicRef
            FontFamilies.GOST_TYPE_B -> gostBRef
            FontFamilies.GOST_TYPE_B_ITALIC -> gostBItalicRef
            FontFamilies.GOST_TYPE_AU -> gostAURef
            FontFamilies.GOST_TYPE_BU -> gostBURef
            else -> regularRef
        }

    companion object {
        fun load(): DefaultFontRegistry {
            val registry = FontRegistry()
            val regularRef = registry.register("pt-sans-regular", loadFontResource("PT_Sans-Regular.ttf"))
            val gostARef = registry.register("gost-type-a", loadFontResource("GOST-Type-A.ttf"))
            val gostAItalicRef = registry.register("gost-type-a-italic", loadFontResource("GOST-Type-A-Italic.ttf"))
            val gostBRef = registry.register("gost-type-b", loadFontResource("GOST-Type-B.ttf"))
            val gostBItalicRef = registry.register("gost-type-b-italic", loadFontResource("GOST-Type-B-Italic.ttf"))
            val gostAURef = registry.register("gost-type-au", loadFontResource("GOST-Type-AU.ttf"))
            val gostBURef = registry.register("gost-type-bu", loadFontResource("GOST-Type-BU.ttf"))
            return DefaultFontRegistry(registry, regularRef, gostARef, gostAItalicRef, gostBRef, gostBItalicRef, gostAURef, gostBURef)
        }

        private fun loadFontResource(name: String): ByteArray =
            requireNotNull(DefaultFontRegistry::class.java.getResourceAsStream("/fonts/$name")) {
                "font resource missing — expected report-layout/src/main/resources/fonts/$name"
            }.readBytes()
    }
}
