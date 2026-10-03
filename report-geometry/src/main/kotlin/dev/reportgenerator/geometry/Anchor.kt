package dev.reportgenerator.geometry

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

fun Rect.corner(corner: Corner): Point = when (corner) {
    Corner.TOP_LEFT -> Point(left, top)
    Corner.TOP_RIGHT -> Point(right, top)
    Corner.BOTTOM_LEFT -> Point(left, bottom)
    Corner.BOTTOM_RIGHT -> Point(right, bottom)
}

// Core placement math: origin (top-left) of a block whose own point `self` (local, from the block's
// top-left) must land on `target + offset`. `offset` is in plain sheet axes: x right, y down.
// Works for any anchor point (corners, edges, cell corners, custom points), not only corners.
fun placeOrigin(target: Point, self: Point, offset: Point = Point(Length.ZERO, Length.ZERO)): Point =
    Point(target.x + offset.x - self.x, target.y + offset.y - self.y)

// Anchors a `size`-sized block so its own `blockCorner` coincides with `baseCorner` of `base`
// (offset further by `offset`, positive = into base's interior). Replaces what used to be 3
// separate hardcoded origin functions (frame/leftMargin/belowFrame) — each is this formula with
// a different (base, baseCorner, blockCorner) triple. `blockCorner == baseCorner` (the default)
// nests the block inside base's corner (e.g. the stamp inside the frame); an opposite blockCorner
// on one axis hangs the block outside base on that axis instead (e.g. leftMarginTable, whose
// right edge — a "RIGHT" blockCorner — touches the frame's LEFT edge from outside).
//
// Offset convention: "inward" is relative to the BLOCK's corner: with a LEFT blockCorner a positive
// x moves the block right, with a RIGHT one it moves it left (same for TOP/BOTTOM on y). The sheet
// axes offset taken by placeOrigin is therefore this offset with sign flipped on RIGHT/BOTTOM
// block corners — that is the conversion used below, and the one to apply when comparing with
// templates (which use sheet axes: x right, y down).
fun resolveAnchor(
    base: Rect,
    baseCorner: Corner,
    blockCorner: Corner = baseCorner,
    size: Size,
    offset: Point = Point(Length.ZERO, Length.ZERO)
): Point {
    val self = Rect(Length.ZERO, Length.ZERO, size.width, size.height).corner(blockCorner)
    val left = blockCorner == Corner.TOP_LEFT || blockCorner == Corner.BOTTOM_LEFT
    val top = blockCorner == Corner.TOP_LEFT || blockCorner == Corner.TOP_RIGHT
    val sheetOffset = Point(if (left) offset.x else -offset.x, if (top) offset.y else -offset.y)
    return placeOrigin(base.corner(baseCorner), self, sheetOffset)
}
