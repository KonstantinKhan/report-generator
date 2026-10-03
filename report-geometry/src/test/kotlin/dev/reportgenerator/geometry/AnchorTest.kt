package dev.reportgenerator.geometry

import kotlin.test.Test
import kotlin.test.assertEquals

class AnchorTest {
    // A4 frame: left 20, top 5, 185 x 287.
    private val frame = Rect(20.mm, 5.mm, 185.mm, 287.mm)

    @Test
    fun `block corner equal to base corner nests inside`() {
        val origin = resolveAnchor(frame, Corner.BOTTOM_RIGHT, size = Size(185.mm, 40.mm))
        assertEquals(Point(20.mm, 252.mm), origin)
    }

    @Test
    fun `opposite block corner hangs outside the base`() {
        val origin = resolveAnchor(frame, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT, Size(12.mm, 135.mm))
        assertEquals(Point(8.mm, 157.mm), origin)
        val top = resolveAnchor(frame, Corner.TOP_LEFT, Corner.TOP_RIGHT, Size(12.mm, 120.mm))
        assertEquals(Point(8.mm, 5.mm), top)
    }

    @Test
    fun `offset is inward relative to the block corner`() {
        val up = resolveAnchor(frame, Corner.BOTTOM_RIGHT, Corner.BOTTOM_RIGHT, Size(120.mm, 22.mm), Point(Length.ZERO, 40.mm))
        assertEquals(Point(85.mm, 230.mm), up)
        val right = resolveAnchor(frame, Corner.TOP_LEFT, Corner.TOP_LEFT, Size(10.mm, 10.mm), Point(3.mm, 2.mm))
        assertEquals(Point(23.mm, 7.mm), right)
    }

    @Test
    fun `placeOrigin uses sheet axes on arbitrary points`() {
        val origin = placeOrigin(Point(100.mm, 50.mm), Point(2.mm, 3.mm), Point(1.mm, -1.mm))
        assertEquals(Point(99.mm, 46.mm), origin)
    }

    @Test
    fun `rect corners`() {
        assertEquals(Point(205.mm, 292.mm), frame.corner(Corner.BOTTOM_RIGHT))
        assertEquals(Point(20.mm, 5.mm), frame.corner(Corner.TOP_LEFT))
    }
}
