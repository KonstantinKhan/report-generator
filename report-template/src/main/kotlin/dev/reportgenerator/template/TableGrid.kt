package dev.reportgenerator.template

// A cell placed on the table grid. `rowIndex` / `cellIndex` locate its declaration (expanded row, position in
// that row's `cells`), `row` / `col` its grid origin (cells covered by a rowSpan from above are skipped).
internal class GridCell(val spec: CellSpec, val rowIndex: Int, val cellIndex: Int, val row: Int, val col: Int)

internal class GridError(val rowIndex: Int, val message: String)

internal class Grid(val cells: List<GridCell>, val errors: List<GridError>)

// Row-major placement with column spans and rowSpans. A row lists only the cells that start in it; columns
// occupied from above are skipped. Every row must end up with all columns covered exactly once.
internal fun layoutGrid(rows: List<FixedRow>, columns: Int): Grid {
    val occupied = Array(rows.size) { BooleanArray(columns) }
    val cells = ArrayList<GridCell>()
    val errors = ArrayList<GridError>()
    rows.forEachIndexed { r, row ->
        val total = occupied[r].count { it } + row.cells.sumOf { it.span }
        if (total != columns) errors += GridError(r, "cell spans add up to $total, table has $columns columns")
        var col = 0
        row.cells.forEachIndexed { i, cell ->
            while (col < columns && occupied[r][col]) col++
            val fits = col + cell.span <= columns && (col until col + cell.span).none { occupied[r][it] }
            if (!fits) {
                if (total == columns) errors += GridError(r, "cell $i overlaps a cell spanning down from a row above")
                return@forEachIndexed
            }
            if (r + cell.rowSpan > rows.size) {
                errors += GridError(r, "cell $i rowSpan ${cell.rowSpan} exceeds the table's ${rows.size} rows")
            }
            for (rr in r until minOf(r + cell.rowSpan, rows.size)) {
                for (c in col until col + cell.span) occupied[rr][c] = true
            }
            cells += GridCell(cell, r, i, r, col)
            col += cell.span
        }
    }
    return Grid(cells, errors)
}

internal fun TableBlock.grid(): Grid = layoutGrid(expandedRows(), columns.size)
