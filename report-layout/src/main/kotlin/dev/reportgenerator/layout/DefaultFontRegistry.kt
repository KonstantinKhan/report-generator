package dev.reportgenerator.layout

import dev.reportgenerator.ir.FontFamilies
import dev.reportgenerator.ir.TextStyle
import dev.reportgenerator.layoutir.FontRef

// GOST Type A: real font not available/licensed — PT Sans Regular remains a stand-in.
// GOST Type B: real ASCON font (KOMPAS-3D), licensed for this use.
class DefaultFontRegistry private constructor(
    val registry: FontRegistry,
    private val regularRef: FontRef,
    private val gostBRef: FontRef
) {
    fun resolve(style: TextStyle): FontRef =
        if (style.fontFamily == FontFamilies.GOST_TYPE_B) gostBRef else regularRef

    companion object {
        fun load(): DefaultFontRegistry {
            val registry = FontRegistry()
            val regularRef = registry.register("gost-type-a", loadFontResource("PT_Sans-Regular.ttf"))
            val gostBRef = registry.register("gost-type-b", loadFontResource("GOST-Type-B.ttf"))
            return DefaultFontRegistry(registry, regularRef, gostBRef)
        }

        private fun loadFontResource(name: String): ByteArray =
            requireNotNull(DefaultFontRegistry::class.java.getResourceAsStream("/fonts/$name")) {
                "font resource missing — expected report-layout/src/main/resources/fonts/$name"
            }.readBytes()
    }
}
