package dev.reportgenerator.ir

// The static-block slots of PageSetup, each bound to a block id of PageSetup.staticTemplate: the template
// decides where the block goes (anchor + offset), on which pages and whether it reserves content space.
// A slot is active when its PageSetup field is non-null.
enum class StaticSlot(val blockId: String) {
    FRAME("stamp"),
    CONTINUATION_FRAME("continuationStamp"),
    LEFT_MARGIN("leftMargin"),
    SPEC_LEFT("specLeft"),
    MAIN_TITLE_RIGHT("mainTitleRight"),
    BELOW_FRAME("belowFrame");

    companion object {
        fun byBlockId(id: String): StaticSlot? = entries.firstOrNull { it.blockId == id }
    }
}
