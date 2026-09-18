package dev.reportgenerator.layout

import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private fun Rect.corner(corner: Corner): Point = when (corner) {
    Corner.TOP_LEFT -> Point(left, top)
    Corner.TOP_RIGHT -> Point(right, top)
    Corner.BOTTOM_LEFT -> Point(left, bottom)
    Corner.BOTTOM_RIGHT -> Point(right, bottom)
}

// Anchors a `size`-sized block so its own `blockCorner` coincides with `baseCorner` of `base`
// (offset further by `offset`, positive = into base's interior). Replaces what used to be 3
// separate hardcoded origin functions (frame/leftMargin/belowFrame) — each is this formula with
// a different (base, baseCorner, blockCorner) triple. `blockCorner == baseCorner` (the default)
// nests the block inside base's corner (e.g. the stamp inside the frame); an opposite blockCorner
// on one axis hangs the block outside base on that axis instead (e.g. leftMarginTable, whose
// right edge — a "RIGHT" blockCorner — touches the frame's LEFT edge from outside).
fun resolveAnchor(
    base: Rect,
    baseCorner: Corner,
    blockCorner: Corner = baseCorner,
    size: Size,
    offset: Point = Point(Length.ZERO, Length.ZERO)
): Point {
    val anchor = base.corner(baseCorner)
    val x = when (blockCorner) {
        Corner.TOP_LEFT, Corner.BOTTOM_LEFT -> anchor.x + offset.x
        Corner.TOP_RIGHT, Corner.BOTTOM_RIGHT -> anchor.x - size.width - offset.x
    }
    val y = when (blockCorner) {
        Corner.TOP_LEFT, Corner.TOP_RIGHT -> anchor.y + offset.y
        Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> anchor.y - size.height - offset.y
    }
    return Point(x, y)
}
