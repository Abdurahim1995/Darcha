package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scroll-to-cell (T31).
 *
 * **The frozen-region trap is the subject, not a corner case.** Frozen rows and
 * columns are drawn *over* the body, so a scroll computed against the whole
 * canvas can park a cell underneath one: on screen by the arithmetic, invisible
 * to the reader, and with nothing anywhere to report it. Most of what follows
 * exists to make that impossible rather than merely absent.
 *
 * The central assertion is [assertLandsInBody], and it deliberately does **not**
 * re-derive the answer. It asks the real `PaneRegions.regions()` where the cell
 * would be drawn and then asks T29's `cellAt` what is at that point. If the cell
 * ends up under a frozen strip, the hit-test returns the *frozen* cell and the
 * test fails — the two halves of the geometry check each other.
 */
class ScrollToCellTest {

    // Header strips, as the renderer passes them in.
    private val originX = 44f
    private val originY = 22f
    private val width = 1080f
    private val height = 1800f

    private fun geometry(
        columnWidths: Map<Int, Double> = emptyMap(),
        rowHeights: Map<Int, Double> = emptyMap(),
    ) = GridGeometry(
        layout = SheetLayout.EMPTY.copy(columnWidths = columnWidths, rowHeights = rowHeights),
    )

    private fun panes(frozenCols: Int, frozenRows: Int, g: GridGeometry = geometry()) =
        PaneRegions(g, FrozenPanes(frozenCols = frozenCols, frozenRows = frozenRows))

    private fun cell(row: Int, col: Int) = CellRange(row, col, row, col)

    /** Generous bounds — used wherever clamping is not the thing under test. */
    private fun openBounds(p: PaneRegions) = ScrollBounds(
        maxScrollX = 1_000_000f,
        maxScrollY = 1_000_000f,
        minScrollX = p.minScrollX,
        minScrollY = p.minScrollY,
    )

    private fun PaneRegions.scroll(
        target: CellRange,
        from: Viewport,
        bounds: ScrollBounds = openBounds(this),
    ) = scrollToShow(target, from, bounds, originX, originY, width, height)

    /**
     * The property the whole task is about: after scrolling, the target is drawn
     * in the BODY region and a hit-test at its position returns the target.
     *
     * Verified against the real region maths and the real hit-test, so a cell
     * hidden under a frozen strip cannot pass — `cellAt` would answer with
     * whatever the frozen strip is showing there instead.
     */
    private fun assertLandsInBody(p: PaneRegions, g: GridGeometry, target: CellRange, vp: Viewport, at: String = "") {
        val regions = p.regions(vp, originX, originY, width, height)
        val body = regions.firstOrNull { it.pane == Pane.BODY }
        assertNotNull("$at: no body region", body)
        body!!

        val x = body.originX + g.screenXOf(target.startCol, body.viewport)
        val y = body.originY + g.screenYOf(target.startRow, body.viewport)

        assertTrue("$at: target left edge $x is left of the body at ${body.left}", x >= body.left)
        assertTrue("$at: target top edge $y is above the body at ${body.top}", y >= body.top)
        assertTrue("$at: target is off the right edge", x < body.right)
        assertTrue("$at: target is off the bottom edge", y < body.bottom)

        assertEquals(
            "$at: hit-testing the target's own position must return the target",
            CellRef(target.startRow, target.startCol),
            regions.cellAt(x + 1f, y + 1f, g),
        )
    }

    // --- the trap, one band at a time ---

    @Test
    fun aTargetBehindTheFrozenRowBand_endsUpBelowIt() {
        val g = geometry()
        val p = panes(frozenCols = 0, frozenRows = 3, g = g)
        // Scrolled far down, then asked for a row just above the current window:
        // the naive answer parks it under the frozen header rows.
        val from = Viewport(0f, g.rowOffset(400), 1f)
        val target = cell(row = 120, col = 2)

        val vp = p.scroll(target, from)
        assertLandsInBody(p, g, target, vp)
    }

    @Test
    fun aTargetBehindTheFrozenColumnBand_endsUpRightOfIt() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 0, g = g)
        val from = Viewport(g.columnOffset(60), 0f, 1f)
        val target = cell(row = 5, col = 9)

        val vp = p.scroll(target, from)
        assertLandsInBody(p, g, target, vp)
    }

    @Test
    fun aTargetBehindTheFrozenCorner_clearsBothBands() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 3, g = g)
        val from = Viewport(g.columnOffset(50), g.rowOffset(500), 1f)
        val target = cell(row = 4, col = 3)

        val vp = p.scroll(target, from)
        assertLandsInBody(p, g, target, vp)
    }

    /**
     * The sweep. Every pane configuration, every zoom, targets scattered on both
     * sides of the current viewport — the property must hold for all of them,
     * not for the three cases someone thought to write down.
     */
    @Test
    fun theTargetLandsInTheBody_forEveryPaneConfigurationAndZoom() {
        val g = geometry(
            columnWidths = mapOf(0 to 30.0, 1 to 4.5, 5 to 22.0),
            rowHeights = mapOf(0 to 40.0, 2 to 9.0, 7 to 31.0),
        )
        val configs = listOf(0 to 0, 1 to 0, 0 to 2, 2 to 3, 3 to 1)
        val targets = listOf(
            cell(0, 0), cell(4, 4), cell(9, 3), cell(200, 40),
            cell(3, 12), cell(50, 1), cell(1, 60),
        )
        val froms = listOf(
            Viewport(0f, 0f, 1f),
            Viewport(g.columnOffset(30), g.rowOffset(300), 1f),
        )

        for ((fc, fr) in configs) {
            val p = panes(fc, fr, g)
            for (zoom in ZOOMS) {
                for (from in froms) {
                    val start = from.copy(
                        scrollX = from.scrollX.coerceAtLeast(p.minScrollX),
                        scrollY = from.scrollY.coerceAtLeast(p.minScrollY),
                        zoom = zoom,
                    )
                    for (t in targets) {
                        // A frozen cell is shown by the frozen strip, not the
                        // body; its own axis is covered by the tests below.
                        if (p.isColumnFrozen(t.startCol) || p.isRowFrozen(t.startRow)) continue
                        val vp = p.scroll(t, start)
                        assertLandsInBody(p, g, t, vp, "frozen=$fc/$fr zoom=$zoom target=${t.startRow},${t.startCol}")
                    }
                }
            }
        }
    }

    // --- already visible: do not move ---

    @Test
    fun aTargetAlreadyComfortablyVisible_doesNotMoveTheViewport() {
        val g = geometry()
        val p = panes(frozenCols = 1, frozenRows = 2, g = g)
        val from = Viewport(p.minScrollX, p.minScrollY, 1f)
        // Well inside the window at zoom 1 on a 1080 x 1800 canvas.
        val vp = p.scroll(cell(row = 6, col = 4), from)

        // Same instance, not merely equal: nothing was recomputed and thrown away.
        assertSame("a visible target must not move the viewport", from, vp)
    }

    @Test
    fun steppingBetweenTwoVisibleTargets_neverScrolls() {
        // The behaviour that makes search feel calm: walking matches inside one
        // screenful must not jerk the sheet about.
        val g = geometry()
        val p = panes(frozenCols = 0, frozenRows = 0, g = g)
        var vp = Viewport(0f, 0f, 1f)
        for (col in 1..4) {
            val next = p.scroll(cell(row = 3, col = col), vp)
            assertSame("column $col was already visible", vp, next)
            vp = next
        }
    }

    // --- margin ---

    /**
     * A cell flush against the frozen band is technically visible and
     * practically unreadable, so the target is placed with half a default cell
     * of clear space in front of it.
     */
    @Test
    fun aTargetScrolledIntoView_getsItsMargin() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 2, g = g)
        val from = Viewport(g.columnOffset(80), g.rowOffset(800), 1f)
        val target = cell(row = 40, col = 20)

        val vp = p.scroll(target, from)

        assertEquals(
            "half a default column of clear space before the target",
            g.columnOffset(20) - g.defaultColumnWidth / 2f,
            vp.scrollX,
            0.01f,
        )
        assertEquals(
            g.rowOffset(40) - g.defaultRowHeight / 2f,
            vp.scrollY,
            0.01f,
        )
    }

    // --- where clamping and the frozen offset fight ---

    /**
     * The first unfrozen column has nowhere further left to go: the margin would
     * put the scroll below the body's own floor. The clamp wins and the margin is
     * lost — but the target must still be **in the body**, which is the invariant
     * that cannot be traded away.
     */
    @Test
    fun theFirstUnfrozenCell_losesItsMarginButNotItsVisibility() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 3, g = g)
        val from = Viewport(g.columnOffset(90), g.rowOffset(900), 1f)
        val target = cell(row = 3, col = 2) // the first cell past both bands

        val vp = p.scroll(target, from)

        assertEquals("clamped to the body's floor", p.minScrollX, vp.scrollX, 0.01f)
        assertEquals(p.minScrollY, vp.scrollY, 0.01f)
        assertLandsInBody(p, g, target, vp)
    }

    @Test
    fun aTargetAtTheFarEdge_isClampedAndStillVisible() {
        val g = geometry()
        val p = panes(frozenCols = 1, frozenRows = 1, g = g)
        val bounds = ScrollBounds(
            maxScrollX = g.columnOffset(30),
            maxScrollY = g.rowOffset(300),
            minScrollX = p.minScrollX,
            minScrollY = p.minScrollY,
        )
        val target = cell(row = 300, col = 30)

        val vp = p.scroll(target, Viewport(p.minScrollX, p.minScrollY, 1f), bounds)

        assertTrue("must not scroll past the end", vp.scrollX <= bounds.maxScrollX + 0.01f)
        assertTrue(vp.scrollY <= bounds.maxScrollY + 0.01f)
        assertLandsInBody(p, g, target, vp)
    }

    @Test
    fun boundsTighterThanTheFrozenFloor_doNotInvertTheClamp() {
        // A degenerate ScrollBounds where max < min — possible for one frame
        // before the renderer publishes real bounds. It must not throw or
        // produce a scroll below the floor.
        val g = geometry()
        val p = panes(frozenCols = 3, frozenRows = 3, g = g)
        val bounds = ScrollBounds(maxScrollX = 0f, maxScrollY = 0f, minScrollX = p.minScrollX, minScrollY = p.minScrollY)

        val vp = p.scroll(cell(row = 10, col = 10), Viewport(p.minScrollX, p.minScrollY, 1f), bounds)

        assertTrue(vp.scrollX >= p.minScrollX - 0.01f)
        assertTrue(vp.scrollY >= p.minScrollY - 0.01f)
    }

    // --- frozen targets ---

    @Test
    fun aFrozenCell_doesNotMoveItsAxis() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 2, g = g)
        val from = Viewport(g.columnOffset(40), g.rowOffset(400), 1f)

        // Frozen on both axes: nothing moves, because it is already on screen.
        assertSame(from, p.scroll(cell(row = 1, col = 1), from))
    }

    @Test
    fun aCellFrozenOnOneAxisOnly_movesOnTheOther() {
        val g = geometry()
        val p = panes(frozenCols = 2, frozenRows = 0, g = g)
        val from = Viewport(g.columnOffset(40), g.rowOffset(400), 1f)

        // Column 1 is frozen, row 12 is not.
        val vp = p.scroll(cell(row = 12, col = 1), from)
        assertEquals("the frozen axis is untouched", from.scrollX, vp.scrollX, 0f)
        assertTrue("the free axis moved", vp.scrollY < from.scrollY)
    }

    // --- merged ranges ---

    @Test
    fun aMergedRangeThatFits_isBroughtIntoViewWhole() {
        val g = geometry()
        val p = panes(frozenCols = 1, frozenRows = 1, g = g)
        val range = CellRange(startRow = 60, startCol = 4, endRow = 62, endCol = 7)

        val vp = p.scroll(range, Viewport(p.minScrollX, p.minScrollY, 1f))

        val rightEdge = g.columnOffset(7) + g.columnWidth(7)
        val bottomEdge = g.rowOffset(62) + g.rowHeight(62)
        val splitX = originX + p.frozenWidth(vp)
        val splitY = originY + p.frozenHeight(vp)
        assertTrue("the far edge must be inside the window", rightEdge <= vp.scrollX + (width - splitX))
        assertTrue(bottomEdge <= vp.scrollY + (height - splitY))
        assertLandsInBody(p, g, range, vp)
    }

    @Test
    fun aMergedRangeTooWideToFit_isFramedFromItsAnchor() {
        val g = geometry()
        val p = panes(frozenCols = 0, frozenRows = 0, g = g)
        // Far wider and taller than the canvas can show at zoom 1.
        val range = CellRange(startRow = 100, startCol = 20, endRow = 200, endCol = 120)

        val vp = p.scroll(range, Viewport(0f, 0f, 1f))

        assertEquals(
            "an unframeable range shows its anchor, with the margin",
            g.columnOffset(20) - g.defaultColumnWidth / 2f,
            vp.scrollX,
            0.01f,
        )
        assertLandsInBody(p, g, range, vp)
    }

    // --- the 1-D solver, on its own ---

    @Test
    fun solveAxis_leavesAVisibleTargetAlone() {
        assertEquals(100f, solveAxisScroll(140f, 160f, 100f, 200f, 10f, 0f, 1000f), 0f)
    }

    @Test
    fun solveAxis_entersFromWhicheverEdgeTheTargetWasOutside() {
        // Below the window: scroll back so the start clears the margin.
        assertEquals(40f, solveAxisScroll(50f, 60f, 300f, 200f, 10f, 0f, 1000f), 0f)
        // Past the window: scroll forward so the end clears it.
        assertEquals(310f, solveAxisScroll(490f, 500f, 0f, 200f, 10f, 0f, 1000f), 0f)
    }

    @Test
    fun solveAxis_neverDemandsMoreMarginThanTheWindowHas() {
        // A margin wider than the window would make every target unplaceable.
        val scroll = solveAxisScroll(500f, 520f, 0f, 40f, 400f, 0f, 10_000f)
        assertTrue("target start must still be at or after the scroll", scroll <= 500f)
    }

    @Test
    fun solveAxis_withNoWindowAtAll_doesNotDivideOrDrift() {
        // Zero extent happens for one frame before the canvas is measured.
        assertEquals(120f, solveAxisScroll(500f, 520f, 120f, 0f, 10f, 0f, 1000f), 0f)
    }

    /**
     * The invariant stated directly, over a wide sweep of inputs: whatever the
     * window, margin or bounds, the scroll never ends up past the target's start.
     * That single inequality is what keeps a cell out from under a frozen strip.
     */
    @Test
    fun solveAxis_neverScrollsPastTheTargetStart() {
        for (start in listOf(0f, 5f, 120f, 999f)) {
            for (span in listOf(1f, 40f, 400f)) {
                for (current in listOf(0f, 50f, 600f)) {
                    for (extent in listOf(10f, 120f, 700f)) {
                        for (margin in listOf(0f, 8f, 300f)) {
                            for (min in listOf(0f, 30f)) {
                                val scroll = solveAxisScroll(
                                    start, start + span, current, extent, margin, min, 5_000f,
                                )
                                assertTrue(
                                    "start=$start span=$span cur=$current ext=$extent " +
                                        "margin=$margin min=$min -> $scroll",
                                    scroll <= start || scroll <= min + 0.001f,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** The zoom range the UI allows, plus awkward values between the stops. */
        val ZOOMS = floatArrayOf(0.5f, 0.73f, 1f, 1.37f, 2f, 3f)
    }
}
