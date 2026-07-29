package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.columnWidthToPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry tests: offsets, visible ranges, hit tests, zoom, and grid bounds. */
class GridGeometryTest {

    private fun layout(
        columnWidths: Map<Int, Double> = emptyMap(),
        rowHeights: Map<Int, Double> = emptyMap(),
        defaultColWidth: Double = SheetLayout.DEFAULT_COL_WIDTH,
        defaultRowHeight: Double = SheetLayout.DEFAULT_ROW_HEIGHT,
    ) = SheetLayout(
        columnWidths = columnWidths,
        rowHeights = rowHeights,
        defaultColWidth = defaultColWidth,
        defaultRowHeight = defaultRowHeight,
        merges = emptyList<CellRange>(),
        frozenPanes = FrozenPanes.NONE,
    )

    /** Default column width in pixels, straight from the central converter. */
    private val defaultColPx = columnWidthToPixels(SheetLayout.DEFAULT_COL_WIDTH).toFloat()

    /** Default row height in pixels (15 pt at 96 DPI). */
    private val defaultRowPx = (SheetLayout.DEFAULT_ROW_HEIGHT * GridGeometry.POINTS_TO_PIXELS_96DPI).toFloat()

    private val eps = 0.01f

    // --- offsets with no custom sizes ---

    @Test
    fun offsets_areMultiplesOfTheDefault() {
        val g = GridGeometry(layout())
        assertEquals(0f, g.columnOffset(0), eps)
        assertEquals(defaultColPx, g.columnOffset(1), eps)
        assertEquals(defaultColPx * 10, g.columnOffset(10), eps)
        assertEquals(0f, g.rowOffset(0), eps)
        assertEquals(defaultRowPx * 7, g.rowOffset(7), eps)
    }

    @Test
    fun sizes_fallBackToTheDefault() {
        val g = GridGeometry(layout())
        assertEquals(defaultColPx, g.columnWidth(0), eps)
        assertEquals(defaultColPx, g.columnWidth(5_000), eps)
        assertEquals(defaultRowPx, g.rowHeight(999_999), eps)
    }

    // --- offsets with custom sizes ---

    @Test
    fun customSizes_shiftEverythingAfterThem() {
        // Column 2 is twice the default width; 0, 1 and 3 are untouched.
        val wide = SheetLayout.DEFAULT_COL_WIDTH * 2
        val widePx = columnWidthToPixels(wide).toFloat()
        val g = GridGeometry(layout(columnWidths = mapOf(2 to wide)))

        assertEquals(defaultColPx * 2, g.columnOffset(2), eps)
        assertEquals(widePx, g.columnWidth(2), eps)
        // Everything past the custom column shifts by the delta, not by its size.
        assertEquals(defaultColPx * 2 + widePx, g.columnOffset(3), eps)
        assertEquals(defaultColPx * 3 + widePx, g.columnOffset(4), eps)
    }

    @Test
    fun severalCustomSizes_accumulate() {
        val g = GridGeometry(
            layout(
                columnWidths = mapOf(0 to 20.0, 1 to 4.0, 5 to 30.0),
                rowHeights = mapOf(1 to 40.0, 2 to 40.0),
            ),
        )
        val c0 = columnWidthToPixels(20.0).toFloat()
        val c1 = columnWidthToPixels(4.0).toFloat()
        val c5 = columnWidthToPixels(30.0).toFloat()

        assertEquals(0f, g.columnOffset(0), eps)
        assertEquals(c0, g.columnOffset(1), eps)
        assertEquals(c0 + c1, g.columnOffset(2), eps)
        assertEquals(c0 + c1 + defaultColPx * 3, g.columnOffset(5), eps)
        assertEquals(c0 + c1 + defaultColPx * 3 + c5, g.columnOffset(6), eps)

        val tall = (40.0 * GridGeometry.POINTS_TO_PIXELS_96DPI).toFloat()
        assertEquals(defaultRowPx, g.rowOffset(1), eps)
        assertEquals(defaultRowPx + tall, g.rowOffset(2), eps)
        assertEquals(defaultRowPx + tall * 2, g.rowOffset(3), eps)
    }

    @Test
    fun customSizeOutOfBounds_isIgnored() {
        // A stray entry beyond the grid must not distort offsets inside it.
        val g = GridGeometry(layout(columnWidths = mapOf(99_999_999 to 500.0)), columnCount = 10)
        assertEquals(defaultColPx * 5, g.columnOffset(5), eps)
    }

    // --- total content size ---

    @Test
    fun contentSize_coversEveryEntry() {
        val g = GridGeometry(layout(), rowCount = 100, columnCount = 20)
        assertEquals(defaultColPx * 20, g.contentWidth, eps)
        assertEquals(defaultRowPx * 100, g.contentHeight, eps)
    }

    // --- visible range ---

    @Test
    fun visibleRange_fromTheOrigin() {
        val g = GridGeometry(layout())
        // A canvas 3.5 columns wide must show 4 columns: the half one still draws.
        val range = g.visibleRange(
            Viewport(),
            canvasWidth = defaultColPx * 3.5f,
            canvasHeight = defaultRowPx * 2.5f,
        )
        assertEquals(0, range.firstColumn)
        assertEquals(3, range.lastColumn)
        assertEquals(0, range.firstRow)
        assertEquals(2, range.lastRow)
    }

    @Test
    fun visibleRange_includesAPartiallyScrolledFirstEntry() {
        val g = GridGeometry(layout())
        // Scrolled half a column: column 0 is still half on screen.
        val range = g.visibleRange(
            Viewport(scrollX = defaultColPx * 0.5f, scrollY = defaultRowPx * 0.5f),
            canvasWidth = defaultColPx * 2f,
            canvasHeight = defaultRowPx * 2f,
        )
        assertEquals(0, range.firstColumn)
        assertEquals(2, range.lastColumn)
        assertEquals(0, range.firstRow)
        assertEquals(2, range.lastRow)
    }

    @Test
    fun visibleRange_onExactBoundaries_excludesTheNextEntry() {
        val g = GridGeometry(layout())
        // Exactly two columns wide, aligned: columns 0 and 1 only. Column 2 starts
        // where the viewport ends and has nothing to draw.
        val range = g.visibleRange(
            Viewport(),
            canvasWidth = defaultColPx * 2f,
            canvasHeight = defaultRowPx * 3f,
        )
        assertEquals(0, range.firstColumn)
        assertEquals(1, range.lastColumn)
        assertEquals(2, range.columnCount)
        assertEquals(0, range.firstRow)
        assertEquals(2, range.lastRow)
    }

    @Test
    fun visibleRange_scrolledDeepIntoTheSheet() {
        val g = GridGeometry(layout())
        val range = g.visibleRange(
            Viewport(scrollX = defaultColPx * 100, scrollY = defaultRowPx * 5_000),
            canvasWidth = defaultColPx * 4f,
            canvasHeight = defaultRowPx * 10f,
        )
        assertEquals(100, range.firstColumn)
        assertEquals(103, range.lastColumn)
        assertEquals(5_000, range.firstRow)
        assertEquals(5_009, range.lastRow)
    }

    @Test
    fun visibleRange_neverRunsPastTheGrid() {
        val g = GridGeometry(layout(), rowCount = 10, columnCount = 5)
        val range = g.visibleRange(Viewport(), canvasWidth = 100_000f, canvasHeight = 100_000f)
        assertEquals(0, range.firstColumn)
        assertEquals(4, range.lastColumn)
        assertEquals(0, range.firstRow)
        assertEquals(9, range.lastRow)
    }

    @Test
    fun visibleRange_isNeverEmpty() {
        val g = GridGeometry(layout())
        // Even a degenerate canvas yields the one cell under the origin.
        val range = g.visibleRange(Viewport(), canvasWidth = 0f, canvasHeight = 0f)
        assertEquals(1, range.rowCount)
        assertEquals(1, range.columnCount)
    }

    // --- zoom ---

    @Test
    fun zoom_shrinksTheVisibleRange() {
        val g = GridGeometry(layout())
        val canvasW = defaultColPx * 4f
        val canvasH = defaultRowPx * 4f

        // Zoomed in, the same canvas covers less content.
        val zoomedIn = g.visibleRange(Viewport(zoom = 2f), canvasW, canvasH)
        assertEquals(1, zoomedIn.lastColumn)
        assertEquals(1, zoomedIn.lastRow)

        // Zoomed out, it covers more.
        val zoomedOut = g.visibleRange(Viewport(zoom = 0.5f), canvasW, canvasH)
        assertEquals(7, zoomedOut.lastColumn)
        assertEquals(7, zoomedOut.lastRow)
    }

    @Test
    fun zoom_scalesScreenPositionsUniformly() {
        val g = GridGeometry(layout())
        for (zoom in listOf(0.5f, 1f, 1.75f, 3f)) {
            val viewport = Viewport(zoom = zoom)
            assertEquals(0f, g.screenXOf(0, viewport), eps)
            assertEquals(defaultColPx * 3 * zoom, g.screenXOf(3, viewport), eps)
            assertEquals(defaultColPx * zoom, g.screenWidthOf(0, viewport), eps)
            assertEquals(defaultRowPx * zoom, g.screenHeightOf(0, viewport), eps)
        }
    }

    @Test
    fun screenPositions_accountForScroll() {
        val g = GridGeometry(layout())
        val viewport = Viewport(scrollX = defaultColPx * 2, scrollY = defaultRowPx * 3, zoom = 2f)
        // The first visible column sits at the left edge.
        assertEquals(0f, g.screenXOf(2, viewport), eps)
        assertEquals(defaultColPx * 2f, g.screenXOf(3, viewport), eps) // one column at zoom 2
        assertEquals(0f, g.screenYOf(3, viewport), eps)
    }

    // --- hit testing ---

    @Test
    fun cellAt_findsTheCellUnderThePoint() {
        val g = GridGeometry(layout())
        assertEquals(CellRef(0, 0), g.cellAt(1f, 1f, Viewport()))
        assertEquals(
            CellRef(row = 2, col = 3),
            g.cellAt(defaultColPx * 3 + 2f, defaultRowPx * 2 + 2f, Viewport()),
        )
    }

    @Test
    fun cellAt_isExactOnEdges() {
        val g = GridGeometry(layout())
        // The pixel at a column's left edge belongs to that column…
        assertEquals(CellRef(0, 1), g.cellAt(defaultColPx, 0f, Viewport()))
        // …and the one just before it to the previous column.
        assertEquals(CellRef(0, 0), g.cellAt(defaultColPx - 0.5f, 0f, Viewport()))
    }

    @Test
    fun cellAt_honoursScrollAndZoom() {
        val g = GridGeometry(layout())
        val viewport = Viewport(scrollX = defaultColPx * 5, scrollY = defaultRowPx * 9, zoom = 2f)
        // The top-left screen pixel is the first scrolled-to cell.
        assertEquals(CellRef(row = 9, col = 5), g.cellAt(0f, 0f, viewport))
        // One zoomed column to the right is the next column.
        assertEquals(CellRef(row = 9, col = 6), g.cellAt(defaultColPx * 2f + 1f, 0f, viewport))
    }

    @Test
    fun cellAt_outsideTheGrid_isNull() {
        val g = GridGeometry(layout(), rowCount = 3, columnCount = 3)
        assertNull(g.cellAt(defaultColPx * 5, 0f, Viewport()))
        assertNull(g.cellAt(0f, defaultRowPx * 5, Viewport()))
        assertNull(g.cellAt(-1f, 0f, Viewport()))
        assertNull(g.cellAt(0f, -1f, Viewport()))
    }

    @Test
    fun cellAt_withCustomSizes() {
        val g = GridGeometry(layout(columnWidths = mapOf(0 to 40.0)))
        val wide = columnWidthToPixels(40.0).toFloat()
        assertEquals(CellRef(0, 0), g.cellAt(wide - 1f, 0f, Viewport()))
        assertEquals(CellRef(0, 1), g.cellAt(wide + 1f, 0f, Viewport()))
    }

    // --- full-size sheets ---

    @Test
    fun fullSizeGrid_isAddressableEndToEnd() {
        val g = GridGeometry(layout())
        assertEquals(GridGeometry.MAX_ROWS, g.rowCount)
        assertEquals(GridGeometry.MAX_COLUMNS, g.columnCount)

        // The far corner resolves without materializing anything.
        val lastRow = GridGeometry.MAX_ROWS - 1
        val lastCol = GridGeometry.MAX_COLUMNS - 1
        assertEquals(defaultRowPx * lastRow, g.rowOffset(lastRow), 1f)
        assertEquals(defaultColPx * lastCol, g.columnOffset(lastCol), 1f)
        assertTrue(g.contentHeight > 0f)
    }

    @Test
    fun fullSizeGrid_visibleRangeStaysSmall() {
        // The point of culling: a phone-sized window over a million rows still
        // draws a handful of cells.
        val g = GridGeometry(layout())
        val range = g.visibleRange(
            Viewport(scrollX = defaultColPx * 9_000, scrollY = defaultRowPx * 900_000),
            canvasWidth = 1_080f,
            canvasHeight = 2_400f,
        )
        assertEquals(9_000, range.firstColumn)
        assertEquals(900_000, range.firstRow)
        assertTrue("columns drawn: ${range.columnCount}", range.columnCount < 40)
        assertTrue("rows drawn: ${range.rowCount}", range.rowCount < 200)
        assertTrue("cells drawn: ${range.cellCount}", range.cellCount < 4_000)
    }

    @Test
    fun sparseCustomSizes_onAHugeSheet_areCheap() {
        // Custom entries scattered across a million rows: cost tracks the number
        // of custom entries, not the row count. Rows 2_000, 4_000 … 1_000_000.
        val heights = (1..500).associate { (it * 2_000) to 30.0 }
        val g = GridGeometry(layout(rowHeights = heights))

        val tall = (30.0 * GridGeometry.POINTS_TO_PIXELS_96DPI).toFloat()
        // Before the first custom row, offsets are pure defaults.
        assertEquals(defaultRowPx * 1_999, g.rowOffset(1_999), eps)
        // Row 2_000 is the first custom one — its own height is not in its offset.
        assertEquals(defaultRowPx * 2_000, g.rowOffset(2_000), eps)
        // Row 2_001 sits after that one taller box.
        assertEquals(defaultRowPx * 2_000 + tall, g.rowOffset(2_001), eps)
        // By row 999_000, 499 custom rows (2_000…998_000) precede it.
        val customsBefore = 499
        val expected = defaultRowPx * (999_000 - customsBefore) + tall * customsBefore
        assertEquals(expected, g.rowOffset(999_000), 1f)
    }

    @Test
    fun offsetsAreMonotonic_acrossCustomAndDefaultRuns() {
        val g = GridGeometry(layout(columnWidths = mapOf(3 to 2.0, 7 to 60.0, 8 to 1.0)))
        var previous = -1f
        for (column in 0..20) {
            val offset = g.columnOffset(column)
            assertTrue("offset must increase at $column", offset > previous)
            previous = offset
        }
    }

    @Test
    fun indexAt_andOffsetOf_areConsistent() {
        // Round-trip: the cell found at a column's own offset is that column.
        val g = GridGeometry(layout(columnWidths = mapOf(2 to 3.0, 5 to 50.0)))
        for (column in 0..12) {
            val x = g.columnOffset(column)
            assertEquals("column $column", CellRef(0, column), g.cellAt(x, 0f, Viewport()))
        }
    }
}
