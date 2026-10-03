package dev.reportgenerator.layout

import dev.reportgenerator.geometry.PageFormat
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.ir.FrameSpec
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.StaticSlot
import dev.reportgenerator.template.BlockSpec
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.Margins
import dev.reportgenerator.template.Orientation
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.RectBlock
import dev.reportgenerator.template.SheetSpec
import dev.reportgenerator.template.SizeSpec
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.lit

internal class PlacedStaticBlock(val slot: StaticSlot, val spec: FrameSpec, val rect: Rect) {
    val origin: Point get() = Point(rect.x, rect.y)
}

// Where the static blocks of a document sit on first and continuation pages, and which rects of
// them reserve content space (feed for PageLayoutMetrics / TemplateResolver.flowRegionFor).
internal class StaticLayout(
    val first: List<PlacedStaticBlock>,
    val continuation: List<PlacedStaticBlock>,
    val firstReserved: List<Rect>,
    val continuationReserved: List<Rect>
)

// Placement, page visibility (`when`) and reservation come from PageSetup.staticTemplate; the content
// drawn is the slot's FrameSpec. The template is resolved with the real sheet (format + margins of the
// PageSetup) and every active slot block sized by its FrameSpec, so custom FrameSpecs of any size are
// anchored by the same rules as the default GOST ones. Slots with a null FrameSpec are left out; template
// blocks that are not slot blocks take part in placement (other blocks may attach to them) but are not drawn.
internal fun layOutStaticBlocks(setup: PageSetup): StaticLayout {
    val specs = StaticSlot.entries.mapNotNull { slot -> setup.frameSpecOf(slot)?.let { slot to it } }.toMap()
    val declared = setup.staticTemplate
    val blocks = declared.blocks.mapNotNull { block ->
        val slot = StaticSlot.byBlockId(block.id)
        when {
            // the flow table spec is validated against the template's own sheet; at layout time the sheet is the
            // document's, and the table is built into the IrTable by the caller, so it does not take part here
            block is FlowBlock -> block.copy(table = null)
            slot == null -> block
            slot !in specs -> null
            else -> sizedAs(block, specs.getValue(slot))
        }
    }
    val template = declared.copy(sheet = sheetOf(setup, declared.sheet), blocks = blocks)

    fun place(kind: PageKind): Pair<List<PlacedStaticBlock>, List<Rect>> {
        val resolved = TemplateResolver.resolve(template, kind)
        val placed = resolved.blocks.mapNotNull { block ->
            StaticSlot.byBlockId(block.id)?.let { PlacedStaticBlock(it, specs.getValue(it), block.rect) }
        }
        return placed to resolved.blocks.filter { it.reserves }.map { it.rect }
    }

    val (first, firstReserved) = place(PageKind.FIRST)
    val (continuation, continuationReserved) = place(PageKind.REST)
    return StaticLayout(first, continuation, firstReserved, continuationReserved)
}

// Same placement data (attach, pages, reserves), size taken from the FrameSpec.
private fun sizedAs(block: BlockSpec, spec: FrameSpec): BlockSpec = RectBlock(
    id = block.id,
    size = SizeSpec(spec.size.width.toMillimeters().lit(), spec.size.height.toMillimeters().lit()),
    anchors = block.anchors,
    attach = block.attach,
    visibleOn = block.visibleOn,
    reserves = block.reserves
)

private fun sheetOf(setup: PageSetup, declared: SheetSpec): SheetSpec {
    val format: PageFormat = setup.format
    val m = setup.margins
    return SheetSpec(
        format = null,
        width = format.width.toMillimeters(),
        height = format.height.toMillimeters(),
        orientation = if (format.width > format.height) Orientation.LANDSCAPE else Orientation.PORTRAIT,
        margins = Margins(
            top = m.top.toMillimeters(), right = m.right.toMillimeters(),
            bottom = m.bottom.toMillimeters(), left = m.left.toMillimeters()
        ),
        anchors = declared.anchors
    )
}
