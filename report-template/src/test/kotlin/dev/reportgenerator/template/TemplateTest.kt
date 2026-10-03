package dev.reportgenerator.template

import dev.reportgenerator.geometry.Corner
import dev.reportgenerator.geometry.Length
import dev.reportgenerator.geometry.Point
import dev.reportgenerator.geometry.Rect
import dev.reportgenerator.geometry.Size
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.geometry.resolveAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateTest {
    private fun resolve(yaml: String, kind: PageKind = PageKind.FIRST) =
        TemplateResolver.resolve(TemplateLoader.load(yaml), kind)

    private fun ResolvedTemplate.rect(id: String): Rect = assertNotNull(block(id), "block $id").rect

    private fun errorOf(yaml: String): String =
        assertFailsWith<TemplateException> { TemplateLoader.load(yaml) }.message!!

    private fun p(x: Double, y: Double) = Point(x.mm, y.mm)

    @Test
    fun `sheet A4 portrait and landscape sizes`() {
        val portrait = resolve("blocks: []").sheet.rect
        assertEquals(Rect(Length.ZERO, Length.ZERO, 210.mm, 297.mm), portrait)

        val landscape = resolve("sheet: {format: A3, orientation: landscape}\nblocks: []").sheet.rect
        assertEquals(420.mm, landscape.width)
        assertEquals(297.mm, landscape.height)

        val custom = resolve("sheet: {width: 300, height: 100}\nblocks: []").sheet.rect
        assertEquals(100.mm, custom.width) // portrait normalizes short side to width
        assertEquals(300.mm, custom.height)
    }

    @Test
    fun `nested attach matches resolveAnchor`() {
        val t = resolve(
            """
            sheet: {margins: {left: 20, top: 5, right: 5, bottom: 5}}
            blocks:
              - {id: frame, type: frame, size: {width: 185, height: 287}, attach: {to: "sheet.contentTopLeft"}}
              - id: stamp
                type: rect
                size: {width: 185, height: 55}
                attach: {self: bottomRight, to: frame.bottomRight, offset: {x: -3, y: -2}}
            """.trimIndent()
        )
        val frame = t.rect("frame")
        // resolveAnchor offset is "inward" (positive = into base), ours is in sheet axes -> flipped sign.
        val expected = resolveAnchor(frame, Corner.BOTTOM_RIGHT, Corner.BOTTOM_RIGHT, Size(185.mm, 55.mm), Point(3.mm, 2.mm))
        val stamp = t.rect("stamp")
        assertEquals(expected, Point(stamp.x, stamp.y))
        assertEquals(frame.right - 3.mm, stamp.right) // inward means left of frame's right edge
    }

    @Test
    fun `hanging attach matches resolveAnchor`() {
        val t = resolve(
            """
            sheet: {margins: {left: 20, top: 5, right: 5, bottom: 5}}
            blocks:
              - {id: frame, type: frame, size: {width: 185, height: 287}, attach: {to: "sheet.contentTopLeft"}}
              - id: left
                type: rect
                size: {width: 7, height: 60}
                attach: {self: bottomRight, to: frame.bottomLeft}
            """.trimIndent()
        )
        val frame = t.rect("frame")
        val expected = resolveAnchor(frame, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT, Size(7.mm, 60.mm))
        val left = t.rect("left")
        assertEquals(expected, Point(left.x, left.y))
        assertEquals(frame.left, left.right)
    }

    @Test
    fun `offset is plain sheet axes on arbitrary anchors`() {
        val t = resolve(
            """
            blocks:
              - id: a
                type: rect
                size: {width: 10, height: 10}
                anchors: {tip: {x: 2, y: 3}}
                attach: {self: tip, to: sheet.center, offset: {x: 1, y: -1}}
            """.trimIndent()
        )
        val a = t.block("a")!!
        assertEquals(p(105.0 + 1 - 2, 148.5 - 1 - 3), Point(a.rect.x, a.rect.y))
        assertEquals(p(105.0 + 1, 148.5 - 1), a.anchors["tip"])
    }

    private val tableYaml = """
        blocks:
          - id: t
            type: table
            columns: [10, 20, 30]
            rows:
              - height: 5
                cells: [a, {text: b, span: 2}]
              - height: 7
                cells: [c, d, e]
              - repeat: {count: 2, row: {height: 4, cells: [x, y, z]}}
            attach: {to: "sheet.topLeft", offset: {x: 100, y: 50}}
          - id: probe
            type: rect
            size: {width: 1, height: 1}
            attach: {to: "t.cell[1,2].bottomRight"}
    """.trimIndent()

    @Test
    fun `table auto anchors`() {
        val t = resolve(tableYaml)
        val table = t.block("t")!!
        assertEquals(Rect(100.mm, 50.mm, 60.mm, 20.mm), table.rect)
        assertEquals(p(110.0, 50.0), table.anchors["col[1].left"])
        assertEquals(p(130.0, 50.0), table.anchors["col[1].right"])
        assertEquals(p(100.0, 55.0), table.anchors["row[1].top"])
        assertEquals(p(100.0, 62.0), table.anchors["row[1].bottom"])
        assertEquals(p(100.0, 70.0), table.anchors["row[3].bottom"])
        assertEquals(p(110.0, 55.0), table.anchors["cell[1,1].topLeft"])
        assertEquals(p(130.0, 58.5), table.anchors["cell[1,2].middleLeft"])
        // spanned cell: starts at col 1, covers cols 1-2
        assertEquals(Rect(110.mm, 50.mm, 50.mm, 5.mm), table.cells.first { it.row == 0 && it.col == 1 }.rect)
        assertNull(table.anchors["cell[0,2].topLeft"])
        assertEquals(p(160.0, 62.0), Point(t.rect("probe").x, t.rect("probe").y))
        assertEquals(11, table.cells.size)
    }

    @Test
    fun `blockset params substitution and nested port attach`() {
        val t = resolve(
            """
            blocksets:
              box:
                params: {w: 10, label: null}
                ports: {right: b.middleRight}
                blocks:
                  - {id: b, type: rect, size: {width: "${'$'}{param.w}", height: 4}}
                  - {id: l, type: text, text: "L=${'$'}{param.label}", size: {width: 1, height: 1}}
              pair:
                params: {w: 10}
                ports: {tail: second.bottomRight}
                blocks:
                  - {id: first, use: box, args: {w: "${'$'}{param.w}", label: A}}
                  - {id: second, use: box, args: {label: B}, attach: {self: middleLeft, to: first.right}}
            blocks:
              - {id: p, use: pair, args: {w: 30}, attach: {to: "sheet.topLeft", offset: {x: 5, y: 5}}}
              - {id: after, type: rect, size: {width: 2, height: 2}, attach: {self: topLeft, to: "p.tail"}}
            """.trimIndent()
        )
        assertEquals(Rect(5.mm, 5.mm, 30.mm, 4.mm), t.rect("p/first/b"))
        // second keeps its own default w=10, glued to first's right edge, vertically centered
        assertEquals(Rect(35.mm, 5.mm, 10.mm, 4.mm), t.rect("p/second/b"))
        assertEquals("L=B", t.block("p/second/l")!!.text)
        assertEquals(Rect(5.mm, 5.mm, 40.mm, 4.mm), t.rect("p"))
        assertEquals(p(45.0, 9.0), Point(t.rect("after").x, t.rect("after").y))
    }

    @Test
    fun `blockset recursion detected`() {
        val msg = errorOf(
            """
            blocksets:
              a: {blocks: [{id: x, use: b}]}
              b: {blocks: [{id: y, use: a}]}
            blocks: []
            """.trimIndent()
        )
        assertTrue("recursive blockset use: a -> b -> a" in msg, msg)
    }

    @Test
    fun `cycle error lists the cycle`() {
        val msg = errorOf(
            """
            blocks:
              - {id: a, type: rect, size: {width: 1, height: 1}, attach: {to: "c.topLeft"}}
              - {id: b, type: rect, size: {width: 1, height: 1}, attach: {to: "a.topLeft"}}
              - {id: c, type: rect, size: {width: 1, height: 1}, attach: {to: "b.topLeft"}}
            """.trimIndent()
        )
        assertTrue("blocks[0].attach.to: attach cycle: a -> c -> b -> a" in msg, msg)
    }

    @Test
    fun `unknown anchor error has yaml path`() {
        val msg = errorOf(
            """
            blocks:
              - {id: top, type: rect, size: {width: 1, height: 1}}
              - id: stamp
                type: table
                columns: [10]
                rows: [{height: 5, cells: [a]}]
              - id: x
                type: rect
                size: {width: 1, height: 1}
                attach: {to: "stamp.cell[9,9].topLeft"}
              - {id: y, type: rect, size: {width: 1, height: 1}, attach: {to: "nope.topLeft"}}
            """.trimIndent()
        )
        assertTrue("blocks[2].attach.to: unknown anchor 'stamp.cell[9,9].topLeft'" in msg, msg)
        assertTrue("blocks[3].attach.to: unknown block 'nope'" in msg, msg)
    }

    @Test
    fun `when first and rest filtering`() {
        val yaml = """
            blocks:
              - {id: all, type: rect, size: {width: 1, height: 1}}
              - {id: f, type: rect, when: first, size: {width: 1, height: 1}}
              - {id: r, type: rect, when: rest, size: {width: 1, height: 1}}
        """.trimIndent()
        assertEquals(listOf("all", "f"), resolve(yaml, PageKind.FIRST).blocks.map { it.id })
        assertEquals(listOf("all", "r"), resolve(yaml, PageKind.REST).blocks.map { it.id })
    }

    @Test
    fun `attach to block missing on some pages is rejected`() {
        val msg = errorOf(
            """
            blocks:
              - {id: f, type: rect, when: first, size: {width: 1, height: 1}}
              - {id: a, type: rect, size: {width: 1, height: 1}, attach: {to: "f.topLeft"}}
            """.trimIndent()
        )
        assertTrue("exists only on first pages" in msg, msg)
    }

    @Test
    fun `flowRegion with two reserves takes the nearest edge`() {
        val t = resolve(
            """
            sheet: {margins: {left: 20, top: 5, right: 5, bottom: 5}}
            blocks:
              - {id: frame, type: frame, size: {width: 185, height: 287}, attach: {to: "sheet.contentTopLeft"}}
              - id: stamp
                type: rect
                reserves: true
                size: {width: 185, height: 40}
                attach: {self: bottomRight, to: frame.bottomRight}
              - id: footer
                type: rect
                reserves: true
                size: {width: 100, height: 60}
                attach: {self: bottomLeft, to: frame.bottomLeft}
              - id: gutter
                type: rect
                reserves: true
                size: {width: 7, height: 200}
                attach: {self: topRight, to: frame.topLeft}
              - {id: body, type: flow}
            """.trimIndent()
        )
        // footer (60) beats stamp (40); gutter lies left of the content column and is ignored
        assertEquals(Rect(20.mm, 5.mm, 185.mm, 227.mm), t.flowRegion)
        assertEquals(t.flowRegion, t.rect("body"))
    }

    @Test
    fun `flowRegion respects page kind and ignores blocks above the content top`() {
        val yaml = """
            sheet: {margins: {top: 10}}
            blocks:
              - id: head
                type: rect
                reserves: true
                size: {width: 210, height: 10}
              - id: s1
                type: rect
                when: first
                reserves: true
                size: {width: 210, height: 50}
                attach: {self: bottomLeft, to: sheet.bottomLeft}
              - id: s2
                type: rect
                when: rest
                reserves: true
                size: {width: 210, height: 10}
                attach: {self: bottomLeft, to: sheet.bottomLeft}
        """.trimIndent()
        // head lies in the top margin (above the content top): not a reserve. Top reserves are not modeled.
        assertEquals(Rect(Length.ZERO, 10.mm, 210.mm, 237.mm), resolve(yaml, PageKind.FIRST).flowRegion)
        assertEquals(Rect(Length.ZERO, 10.mm, 210.mm, 277.mm), resolve(yaml, PageKind.REST).flowRegion)
    }

    @Test
    fun `mini-spec example loads and resolves`() {
        val text = javaClass.getResource("/templates/mini-spec.yaml")!!.readText()
        val template = TemplateLoader.load(text)
        val first = TemplateResolver.resolve(template, PageKind.FIRST)
        val rest = TemplateResolver.resolve(template, PageKind.REST)
        assertTrue(first.block("stamp") != null && first.block("stampRest") == null)
        assertTrue(rest.block("stampRest") != null && rest.block("stamp") == null)
        // stamp: 15 + 8 + 2*5 tall, bottom-right of frame
        assertEquals(33.mm, first.rect("stamp").height)
        assertEquals(first.rect("frame").bottom - 33.mm, first.flowRegion.bottom)
        assertEquals(rest.rect("frame").bottom - 8.mm, rest.flowRegion.bottom)
        // nested blocksets hang outside the frame's left edge
        assertEquals(first.rect("frame").left, first.rect("margin").right)
        assertNotNull(first.block("margin/lower/caption"))
        assertEquals("Sign and date", first.block("margin/lower/caption")!!.text)
        assertEquals(first.rect("margin").left, first.rect("note").right)
    }

    @Test
    fun `parse errors point at the field`() {
        val msg = errorOf("blocks:\n  - {id: a, type: rect, size: {width: 1, height: 1}, colour: red}")
        assertTrue("blocks[0].colour: unknown field 'colour'" in msg, msg)
        val msg2 = errorOf("blocks:\n  - {id: a, type: rect, size: {width: x, height: 1}}")
        assertTrue("blocks[0].size.width: expected number" in msg2, msg2)
    }

    // ---- per-axis attach ----

    @Test
    fun `per-axis attach places x and y independently against different blocks`() {
        val t = resolve(
            """
            blocks:
              - {id: a, type: rect, size: {width: 10, height: 10}, attach: {to: sheet.topLeft, offset: {x: 10, y: 20}}}
              - {id: c, type: rect, size: {width: 5, height: 5}, attach: {to: sheet.topLeft, offset: {x: 50, y: 100}}}
              - id: b
                type: rect
                size: {width: 4, height: 4}
                attach:
                  x: {self: topLeft, to: a.topRight, offset: 3}
                  y: {self: bottomLeft, to: c.bottomLeft, offset: -1}
            """.trimIndent()
        )
        // x: 20 + 3 - 0; y: 105 - 1 - 4
        assertEquals(Rect(23.mm, 100.mm, 4.mm, 4.mm), t.rect("b"))
    }

    @Test
    fun `single attach equals the same per-axis attach`() {
        val single = resolve(
            """
            blocks:
              - {id: a, type: rect, size: {width: 10, height: 10}, attach: {self: bottomRight, to: sheet.center, offset: {x: 2, y: -3}}}
            """.trimIndent()
        ).rect("a")
        val axes = resolve(
            """
            blocks:
              - id: a
                type: rect
                size: {width: 10, height: 10}
                attach:
                  x: {self: bottomRight, to: sheet.center, offset: 2}
                  y: {self: bottomRight, to: sheet.center, offset: -3}
            """.trimIndent()
        ).rect("a")
        assertEquals(single, axes)
    }

    @Test
    fun `per-axis attach errors carry the axis path and cycles see both axes`() {
        val msg = errorOf(
            """
            blocks:
              - {id: a, type: rect, size: {width: 1, height: 1}}
              - id: b
                type: rect
                size: {width: 1, height: 1}
                attach:
                  x: {to: a.topLeft}
                  y: {to: nope.topLeft}
            """.trimIndent()
        )
        assertTrue("blocks[1].attach.y.to: unknown block 'nope'" in msg, msg)

        val cycle = errorOf(
            """
            blocks:
              - id: a
                type: rect
                size: {width: 1, height: 1}
                attach:
                  x: {to: sheet.topLeft}
                  y: {to: b.topLeft}
              - {id: b, type: rect, size: {width: 1, height: 1}, attach: {to: a.topLeft}}
            """.trimIndent()
        )
        assertTrue("attach cycle: a -> b -> a" in cycle, cycle)

        val missing = errorOf(
            """
            blocks:
              - {id: a, type: rect, size: {width: 1, height: 1}, attach: {x: {to: sheet.topLeft}}}
            """.trimIndent()
        )
        assertTrue("blocks[0].attach: missing required field 'y'" in missing, missing)
    }

    // ---- table rotation ----

    // 30 x 12 table (cols 10+20, rows 5+7) placed at (100, 50)
    // `restRotate`: rotate of every cell but `a` (a 270 table needs 90 everywhere to be drawable)
    private fun rotatedTable(rotate: Int, cellRotate: Int = 0, restRotate: Int = 0) = resolve(
        """
        blocks:
          - id: t
            type: table
            rotate: $rotate
            columns: [10, 20]
            rows:
              - height: 5
                cells: [{text: a, rotate: $cellRotate}, {text: b, rotate: $restRotate}]
              - height: 7
                cells: [{text: c, rotate: $restRotate}, {text: d, rotate: $restRotate}]
            anchors: {mark: {x: 4, y: 1}}
            attach: {to: sheet.topLeft, offset: {x: 100, y: 50}}
          - id: probe
            type: rect
            size: {width: 1, height: 1}
            attach: {self: topLeft, to: "t.cell[1,1].bottomRight"}
        """.trimIndent()
    )

    @Test
    fun `table rotated 90 counterclockwise maps rect anchors and cells`() {
        val t = rotatedTable(90)
        val table = t.block("t")!!
        assertEquals(Rect(100.mm, 50.mm, 12.mm, 30.mm), table.rect) // h x w bounding box at the placement point
        assertEquals(p(100.0, 80.0), table.anchors["col[0].left"]) // logical top edge becomes the left edge
        assertEquals(p(100.0, 70.0), table.anchors["col[1].left"])
        assertEquals(p(100.0, 50.0), table.anchors["col[1].right"])
        assertEquals(p(105.0, 80.0), table.anchors["row[1].top"]) // logical left edge becomes the bottom edge
        assertEquals(p(112.0, 80.0), table.anchors["row[1].bottom"])
        assertEquals(p(101.0, 76.0), table.anchors["mark"]) // custom point (4,1) -> (1, 30-4)
        assertEquals(Rect(100.mm, 70.mm, 5.mm, 10.mm), table.cells.first { it.row == 0 && it.col == 0 }.rect)
        assertEquals(p(105.0, 50.0), table.anchors["cell[1,1].topLeft"])
        assertEquals(p(112.0, 80.0), table.anchors["bottomRight"])
        assertEquals(90, table.rotate)
    }

    @Test
    fun `table rotated 270 clockwise`() {
        val table = rotatedTable(270, 90, 90).block("t")!!
        assertEquals(Rect(100.mm, 50.mm, 12.mm, 30.mm), table.rect)
        assertEquals(Rect(107.mm, 50.mm, 5.mm, 10.mm), table.cells.first { it.row == 0 && it.col == 0 }.rect)
        assertEquals(p(112.0, 60.0), table.anchors["col[1].left"])
        assertEquals(p(111.0, 54.0), table.anchors["mark"]) // (4,1) -> (h-1, 4)
    }

    @Test
    fun `attach to a rotated table cell uses the rotated geometry`() {
        val t = rotatedTable(90)
        // cell[1,1] covers (105,50)-(112,70) after rotation; probe sits on its bottomRight
        assertEquals(p(112.0, 70.0), Point(t.rect("probe").x, t.rect("probe").y))
    }

    @Test
    fun `cell text rotation composes with block rotation`() {
        fun rot(block: Int, cell: Int) = rotatedTable(block, cell, if (block == 270) 90 else 0).block("t")!!.cells.first { it.text == "a" }.rotate
        assertEquals(0, rot(0, 0))
        assertEquals(90, rot(90, 0))
        assertEquals(0, rot(270, 90))
    }

    @Test
    fun `text rotation the Layout IR cannot draw is a validation error with a yaml path`() {
        val text = errorOf("blocks:\n  - {id: t, type: text, text: x, rotate: 270, size: {width: 10, height: 10}}")
        assertTrue("blocks[0].rotate: text rotation 270 is not supported (expected 0|90)" in text, text)

        // the cell is drawn rotated by the table rotation plus its own: 270 + 0 and 90 + 90 cannot be drawn
        val tableOf = { block: Int, cell: Int ->
            "blocks:\n  - {id: t, type: table, rotate: $block, columns: [10], rows: [{height: 5, cells: [{text: a, rotate: $cell}]}]}"
        }
        val a = errorOf(tableOf(270, 0))
        assertTrue("blocks[0].rows[0].cells[0].rotate: text rotation 270 (table rotate 270 + cell rotate 0) is not supported" in a, a)
        val b = errorOf(tableOf(90, 90))
        assertTrue("text rotation 180 (table rotate 90 + cell rotate 90) is not supported" in b, b)
        TemplateLoader.load(tableOf(270, 90))
        TemplateLoader.load(tableOf(90, 0))
    }

    @Test
    fun `rotated table borders follow the sides`() {
        val t = resolve(
            """
            blocks:
              - id: t
                type: table
                rotate: 90
                borders: thin
                columns: [10]
                rows: [{height: 5, cells: [{text: a, borders: {top: thick, left: none}}]}]
            """.trimIndent()
        )
        val b = t.block("t")!!.cells.single().borders
        // logical top -> left, logical left -> bottom; the rest stay thin
        assertEquals(LineWeight.THICK, b.left)
        assertEquals(LineWeight.NONE, b.bottom)
        assertEquals(LineWeight.THIN, b.top)
        assertEquals(LineWeight.THIN, b.right)
    }

    @Test
    fun `table rotate must be 0, 90 or 270`() {
        val msg = errorOf("blocks:\n  - {id: t, type: table, rotate: 45, columns: [1], rows: [{height: 1, cells: [a]}]}")
        assertTrue("blocks[0].rotate: expected 0|90|270" in msg, msg)
    }

    // ---- rowSpan, borders, thickness ----

    @Test
    fun `rowSpan cell covers rows below and later rows skip its columns`() {
        val t = resolve(
            """
            blocks:
              - id: t
                type: table
                columns: [10, 10, 10]
                rows:
                  - {height: 5, cells: [a, {text: m, rowSpan: 2}, c]}
                  - {height: 5, cells: [d, e]}
            """.trimIndent()
        )
        val table = t.block("t")!!
        assertEquals(Rect(10.mm, 0.mm, 10.mm, 10.mm), table.cells.single { it.text == "m" }.rect)
        assertEquals(Rect(20.mm, 5.mm, 10.mm, 5.mm), table.cells.single { it.text == "e" }.rect)
        assertEquals(p(20.0, 5.0), table.anchors["cell[1,2].topLeft"])
        assertNull(table.anchors["cell[1,1].topLeft"])
    }

    @Test
    fun `rowSpan shape errors`() {
        val over = errorOf(
            """
            blocks:
              - id: t
                type: table
                columns: [10, 10]
                rows:
                  - {height: 5, cells: [{text: a, rowSpan: 2}, b]}
                  - {height: 5, cells: [c, d]}
            """.trimIndent()
        )
        assertTrue("blocks[0].rows[1].cells: cell spans add up to 3, table has 2 columns" in over, over)
        val tooTall = errorOf(
            "blocks:\n  - {id: t, type: table, columns: [10], rows: [{height: 5, cells: [{text: a, rowSpan: 2}]}]}"
        )
        assertTrue("rowSpan 2 exceeds the table's 1 rows" in tooTall, tooTall)
    }

    @Test
    fun `cell borders inherit cell then table then thick`() {
        val t = resolve(
            """
            blocks:
              - id: t
                type: table
                borders: {bottom: thin}
                columns: [10, 10, 10]
                rows:
                  - height: 5
                    cells:
                      - ~
                      - {borders: {top: none}}
                      - {borders: none}
            """.trimIndent()
        )
        val cells = t.block("t")!!.cells
        assertEquals(ResolvedBorders(LineWeight.THICK, LineWeight.THICK, LineWeight.THIN, LineWeight.THICK), cells[0].borders)
        assertEquals(ResolvedBorders(LineWeight.NONE, LineWeight.THICK, LineWeight.THIN, LineWeight.THICK), cells[1].borders)
        assertTrue(cells[2].borders.isNone)
    }

    @Test
    fun `default thickness matches the engine border weights`() {
        val t = resolve(
            """
            blocks:
              - {id: f, type: frame, size: {width: 10, height: 10}}
              - {id: r, type: rect, size: {width: 10, height: 10}}
              - {id: x, type: rect, thickness: thick, size: {width: 10, height: 10}}
              - {id: y, type: rect, thickness: 0.5, size: {width: 10, height: 10}}
            """.trimIndent()
        )
        assertEquals(71, t.block("f")!!.thickness!!.raw) // Styles.tableBorder 2.0pt
        assertEquals(25, t.block("r")!!.thickness!!.raw) // Styles.tableBorderThin 0.7pt
        assertEquals(71, t.block("x")!!.thickness!!.raw)
        assertEquals(50, t.block("y")!!.thickness!!.raw)
    }

    @Test
    fun `repeat count expands rows`() {
        val t = resolve(
            """
            blocks:
              - id: t
                type: table
                columns: [10]
                rows:
                  - {height: 5, cells: [a]}
                  - repeat: {count: 3, row: {height: 2, cells: [x]}}
            """.trimIndent()
        )
        assertEquals(11.mm, t.rect("t").height)
        assertEquals(4, t.block("t")!!.cells.size)
    }

    @Test
    fun `flowRegionFor uses the engine bottom rule`() {
        val content = Rect(20.mm, 15.mm, 185.mm, 277.mm)
        val region = TemplateResolver.flowRegionFor(
            content,
            listOf(
                Rect(20.mm, 252.mm, 185.mm, 40.mm), // stamp
                Rect(8.mm, 157.mm, 12.mm, 135.mm), // gutter: left of the column, ignored
                Rect(85.mm, 230.mm, 120.mm, 22.mm), // nearest edge wins
                Rect(20.mm, 5.mm, 185.mm, 5.mm), // above content top, ignored
                Rect(90.mm, 292.mm, 120.mm, 5.mm) // below the area, no effect
            )
        )
        assertEquals(Rect(20.mm, 15.mm, 185.mm, 215.mm), region)
    }
}
