package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Span bounds for merged ranges (T18): the pixel extent an anchor is drawn
 * across.
 *
 * Measured edge to edge rather than by summing widths, so these tests pin two
 * things — that the arithmetic matches the per-cell sizes it must line up with,
 * and that it stays right when the columns inside the span are not uniform.
 */
class SpanBoundsTest {

    private fun geometry(
        columnWidths: Map<Int, Double> = emptyMap(),
        rowHeights: Map<Int, Double> = emptyMap(),
    ) = GridGeometry(
        layout = SheetLayout.EMPTY.copy(columnWidths = columnWidths, rowHeights = rowHeights),
    )

    private val origin = Viewport(scrollX = 0f, scrollY = 0f, zoom = 1f)

    @Test
    fun aSingleCellSpan_matchesThatCellsSize() {
        val g = geometry()
        assertEquals(g.screenWidthOf(3, origin), g.spanWidthOf(3, 3, origin), EPSILON)
        assertEquals(g.screenHeightOf(7, origin), g.spanHeightOf(7, 7, origin), EPSILON)
    }

    @Test
    fun aSpanOfDefaultSizedCells_isTheSumOfThem() {
        val g = geometry()
        val threeColumns = (0..2).sumOf { g.screenWidthOf(it, origin).toDouble() }.toFloat()
        assertEquals(threeColumns, g.spanWidthOf(0, 2, origin), EPSILON)

        val fourRows = (1..4).sumOf { g.screenHeightOf(it, origin).toDouble() }.toFloat()
        assertEquals(fourRows, g.spanHeightOf(1, 4, origin), EPSILON)
    }

    /** The case summing per-cell sizes would get wrong if the axis were sloppy. */
    @Test
    fun aSpanOverCustomSizedCells_addsUpExactly() {
        val g = geometry(
            columnWidths = mapOf(1 to 30.0, 2 to 4.5),
            rowHeights = mapOf(2 to 40.0),
        )
        val columns = (0..3).sumOf { g.screenWidthOf(it, origin).toDouble() }.toFloat()
        assertEquals(columns, g.spanWidthOf(0, 3, origin), EPSILON)

        val rows = (1..3).sumOf { g.screenHeightOf(it, origin).toDouble() }.toFloat()
        assertEquals(rows, g.spanHeightOf(1, 3, origin), EPSILON)
    }

    @Test
    fun aSpanScalesWithZoom() {
        val g = geometry()
        val atOne = g.spanWidthOf(0, 2, origin)
        val atTwo = g.spanWidthOf(0, 2, origin.copy(zoom = 2f))
        assertEquals(atOne * 2f, atTwo, EPSILON)

        val tallAtHalf = g.spanHeightOf(0, 3, origin.copy(zoom = 0.5f))
        assertEquals(g.spanHeightOf(0, 3, origin) * 0.5f, tallAtHalf, EPSILON)
    }

    /** Scrolling moves where a span starts, never how wide it is. */
    @Test
    fun aSpanIsUnaffectedByScroll() {
        val g = geometry(columnWidths = mapOf(1 to 30.0))
        val scrolled = origin.copy(scrollX = 500f, scrollY = 900f)
        assertEquals(g.spanWidthOf(0, 3, origin), g.spanWidthOf(0, 3, scrolled), EPSILON)
        assertEquals(g.spanHeightOf(0, 3, origin), g.spanHeightOf(0, 3, scrolled), EPSILON)
    }

    /**
     * A span's left edge is its anchor's left edge, and its right edge is the
     * left edge of the column after it — which is what makes the outline land on
     * the gridlines instead of half a pixel off.
     */
    @Test
    fun aSpanEndsWhereTheNextCellBegins() {
        val g = geometry(columnWidths = mapOf(2 to 25.0))
        val left = g.screenXOf(1, origin)
        val right = left + g.spanWidthOf(1, 3, origin)
        assertEquals(g.screenXOf(4, origin), right, EPSILON)

        val top = g.screenYOf(2, origin)
        val bottom = top + g.spanHeightOf(2, 5, origin)
        assertEquals(g.screenYOf(6, origin), bottom, EPSILON)
    }

    /** A merge may be huge; the cost of measuring it must not grow with it. */
    @Test
    fun aSpanOverTheWholeSheet_isStillJustTwoOffsets() {
        val g = geometry()
        val full = g.spanHeightOf(0, GridGeometry.MAX_ROWS - 1, origin)
        assertEquals(g.contentHeight, full, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
