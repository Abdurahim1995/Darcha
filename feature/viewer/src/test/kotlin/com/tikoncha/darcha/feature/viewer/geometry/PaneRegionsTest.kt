package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.FrozenPanes
import com.tikoncha.darcha.model.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Region bounds for frozen panes (T19).
 *
 * The acceptance criterion for T19 is **no seams**, and a seam is an arithmetic
 * fact before it is a visual one: two regions that should meet at a boundary
 * must produce the *same* coordinate for it. These tests assert that equality
 * directly, across zoom levels and scroll positions, which is stronger than
 * looking at a screenshot at one zoom.
 */
class PaneRegionsTest {

    private val origin = Viewport(scrollX = 0f, scrollY = 0f, zoom = 1f)

    /** Header strips, as the renderer passes them in. */
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

    private fun panes(
        frozenCols: Int,
        frozenRows: Int,
        geometry: GridGeometry = geometry(),
    ) = PaneRegions(geometry, FrozenPanes(frozenCols = frozenCols, frozenRows = frozenRows))

    private fun regionsOf(p: PaneRegions, viewport: Viewport) =
        p.regions(viewport, originX, originY, width, height)

    private fun List<PaneRegion>.pane(pane: Pane) = firstOrNull { it.pane == pane }

    // --- which regions exist ---

    @Test
    fun anUnfrozenSheet_isASingleBodyRegion() {
        val p = panes(frozenCols = 0, frozenRows = 0)
        val regions = regionsOf(p, origin)

        assertEquals(1, regions.size)
        val body = regions.single()
        assertEquals(Pane.BODY, body.pane)
        // The body covers the whole grid area, exactly as before freezing existed.
        assertEquals(originX, body.left, EPSILON)
        assertEquals(originY, body.top, EPSILON)
        assertEquals(width, body.right, EPSILON)
        assertEquals(height, body.bottom, EPSILON)
        assertEquals(0f, p.minScrollX, EPSILON)
        assertEquals(0f, p.minScrollY, EPSILON)
    }

    /** `excel/frozen.xlsx` — xSplit=1 with no ySplit. */
    @Test
    fun freezingColumnsOnly_yieldsBodyAndLeftStrip() {
        val regions = regionsOf(panes(frozenCols = 1, frozenRows = 0), origin)

        assertEquals(2, regions.size)
        assertNotNull(regions.pane(Pane.BODY))
        assertNotNull(regions.pane(Pane.LEFT))
        assertNull("nothing is frozen vertically", regions.pane(Pane.TOP))
        assertNull(regions.pane(Pane.CORNER))
        // The left strip owns the full height: there is no horizontal split.
        assertEquals(originY, regions.pane(Pane.LEFT)!!.top, EPSILON)
    }

    /** A sheet frozen only on the row axis — the mirror case. */
    @Test
    fun freezingRowsOnly_yieldsBodyAndTopStrip() {
        val regions = regionsOf(panes(frozenCols = 0, frozenRows = 2), origin)

        assertEquals(2, regions.size)
        assertNotNull(regions.pane(Pane.TOP))
        assertNull(regions.pane(Pane.LEFT))
        assertNull(regions.pane(Pane.CORNER))
        assertEquals(originX, regions.pane(Pane.TOP)!!.left, EPSILON)
    }

    /** `excel/frozen-both.xlsx` — xSplit=1, ySplit=2. */
    @Test
    fun freezingBothAxes_yieldsAllFourRegions() {
        val regions = regionsOf(panes(frozenCols = 1, frozenRows = 2), origin)

        assertEquals(4, regions.size)
        for (pane in Pane.entries) assertNotNull("missing $pane", regions.pane(pane))
        // The body is drawn first so the frozen strips cover it where they meet.
        assertEquals(Pane.BODY, regions.first().pane)
    }

    // --- seams: the acceptance criterion ---

    /**
     * The regions must tile the grid area exactly. Every shared edge is asserted
     * as an *equality*, not a near-equality, because both sides are computed
     * from the same value — if that ever stops being true, this fails.
     */
    @Test
    fun regionsTileTheGridArea_atEveryZoom() {
        val g = geometry(columnWidths = mapOf(0 to 22.5, 1 to 4.0), rowHeights = mapOf(0 to 31.0))
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)

        for (zoom in ZOOMS) {
            val viewport = Viewport(scrollX = p.minScrollX, scrollY = p.minScrollY, zoom = zoom)
            val regions = regionsOf(p, viewport)
            val corner = regions.pane(Pane.CORNER)!!
            val top = regions.pane(Pane.TOP)!!
            val left = regions.pane(Pane.LEFT)!!
            val body = regions.pane(Pane.BODY)!!
            val at = "zoom $zoom"

            // Vertical seam: corner|top on the left, left|body on the right.
            assertEquals(at, corner.right, top.left, 0f)
            assertEquals(at, left.right, body.left, 0f)
            assertEquals("the same vertical seam for both rows of regions", corner.right, left.right, 0f)

            // Horizontal seam: corner|left above, top|body below.
            assertEquals(at, corner.bottom, left.top, 0f)
            assertEquals(at, top.bottom, body.top, 0f)
            assertEquals("the same horizontal seam for both columns", corner.bottom, top.bottom, 0f)

            // And the four together cover the area with nothing left over.
            assertEquals(at, originX, corner.left, 0f)
            assertEquals(at, originY, corner.top, 0f)
            assertEquals(at, width, body.right, 0f)
            assertEquals(at, height, body.bottom, 0f)
        }
    }

    /**
     * The seam must land exactly where the gridline after the last frozen column
     * lands, or a drawn line would sit beside the boundary instead of on it.
     */
    @Test
    fun theSeamIsTheGridlineAfterTheLastFrozenCell_atEveryZoom() {
        val g = geometry(columnWidths = mapOf(0 to 22.5), rowHeights = mapOf(0 to 31.0, 1 to 8.0))
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)

        for (zoom in ZOOMS) {
            val frozenViewport = Viewport(scrollX = 0f, scrollY = 0f, zoom = zoom)
            val viewport = Viewport(scrollX = p.minScrollX, scrollY = p.minScrollY, zoom = zoom)
            val corner = regionsOf(p, viewport).pane(Pane.CORNER)!!

            // Where the corner region itself would draw the boundary gridline.
            val gridlineX = originX + g.screenXOf(1, frozenViewport)
            val gridlineY = originY + g.screenYOf(2, frozenViewport)
            assertEquals("zoom $zoom", gridlineX, corner.right, 0f)
            assertEquals("zoom $zoom", gridlineY, corner.bottom, 0f)
        }
    }

    /**
     * Scrolling must move the body and the strips that share its scrolling axis,
     * and nothing else. A seam that moved with scroll would tear.
     */
    @Test
    fun theSeamDoesNotMoveWithScroll() {
        val p = panes(frozenCols = 1, frozenRows = 2)
        val atRest = regionsOf(p, Viewport(p.minScrollX, p.minScrollY, 1f))
        val scrolled = regionsOf(p, Viewport(p.minScrollX + 4000f, p.minScrollY + 9000f, 1f))

        assertEquals(atRest.pane(Pane.CORNER)!!.right, scrolled.pane(Pane.CORNER)!!.right, 0f)
        assertEquals(atRest.pane(Pane.CORNER)!!.bottom, scrolled.pane(Pane.CORNER)!!.bottom, 0f)
        assertEquals(atRest.pane(Pane.BODY)!!.left, scrolled.pane(Pane.BODY)!!.left, 0f)
        assertEquals(atRest.pane(Pane.BODY)!!.top, scrolled.pane(Pane.BODY)!!.top, 0f)
    }

    // --- translated origins ---

    /** A frozen region reads the sheet as if it were never scrolled. */
    @Test
    fun frozenAxesHaveTheirScrollZeroed() {
        val p = panes(frozenCols = 1, frozenRows = 2)
        val viewport = Viewport(scrollX = p.minScrollX + 500f, scrollY = p.minScrollY + 700f, 1f)
        val regions = regionsOf(p, viewport)

        assertEquals(0f, regions.pane(Pane.CORNER)!!.viewport.scrollX, EPSILON)
        assertEquals(0f, regions.pane(Pane.CORNER)!!.viewport.scrollY, EPSILON)
        // The top strip scrolls sideways with the body but never vertically.
        assertEquals(viewport.scrollX, regions.pane(Pane.TOP)!!.viewport.scrollX, EPSILON)
        assertEquals(0f, regions.pane(Pane.TOP)!!.viewport.scrollY, EPSILON)
        // And the left strip is the mirror.
        assertEquals(0f, regions.pane(Pane.LEFT)!!.viewport.scrollX, EPSILON)
        assertEquals(viewport.scrollY, regions.pane(Pane.LEFT)!!.viewport.scrollY, EPSILON)
        // The body scrolls on both.
        assertEquals(viewport.scrollX, regions.pane(Pane.BODY)!!.viewport.scrollX, EPSILON)
        assertEquals(viewport.scrollY, regions.pane(Pane.BODY)!!.viewport.scrollY, EPSILON)
    }

    /**
     * At rest, the first unfrozen cell must land exactly on the seam — this is
     * what makes the body continue the grid instead of restarting it.
     */
    @Test
    fun atRest_theFirstUnfrozenCellSitsOnTheSeam() {
        val g = geometry(columnWidths = mapOf(0 to 22.5), rowHeights = mapOf(0 to 31.0, 1 to 8.0))
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)

        for (zoom in ZOOMS) {
            val viewport = Viewport(p.minScrollX, p.minScrollY, zoom)
            val body = regionsOf(p, viewport).pane(Pane.BODY)!!
            val firstCellX = body.originX + g.screenXOf(1, body.viewport)
            val firstCellY = body.originY + g.screenYOf(2, body.viewport)
            assertEquals("zoom $zoom", body.left, firstCellX, SUBPIXEL)
            assertEquals("zoom $zoom", body.top, firstCellY, SUBPIXEL)
        }
    }

    // --- ranges ---

    @Test
    fun eachRegionOwnsItsOwnCells_withNoOverlap() {
        val p = panes(frozenCols = 1, frozenRows = 2)
        val regions = regionsOf(p, Viewport(p.minScrollX, p.minScrollY, 1f))

        val corner = regions.pane(Pane.CORNER)!!
        val body = regions.pane(Pane.BODY)!!
        assertEquals(0, corner.firstRow)
        assertEquals(1, corner.lastRow)
        assertEquals(0, corner.firstColumn)
        assertEquals(0, corner.lastColumn)
        // The body starts past both frozen strips.
        assertTrue("body starts below the frozen rows", body.firstRow >= 2)
        assertTrue("body starts right of the frozen columns", body.firstColumn >= 1)
    }

    /** The scrolling strips agree with the body on the axis they share. */
    @Test
    fun stripsShareTheBodysScrollingRange() {
        val p = panes(frozenCols = 1, frozenRows = 2)
        val regions = regionsOf(p, Viewport(p.minScrollX + 3000f, p.minScrollY + 5000f, 1f))
        val body = regions.pane(Pane.BODY)!!

        assertEquals(body.firstColumn, regions.pane(Pane.TOP)!!.firstColumn)
        assertEquals(body.lastColumn, regions.pane(Pane.TOP)!!.lastColumn)
        assertEquals(body.firstRow, regions.pane(Pane.LEFT)!!.firstRow)
        assertEquals(body.lastRow, regions.pane(Pane.LEFT)!!.lastRow)
    }

    // --- degenerate cases ---

    /**
     * A sheet frozen wider than the screen would leave the body a negative
     * rectangle. It has to stay well-formed instead — the strips simply take
     * everything.
     */
    @Test
    fun aFrozenStripWiderThanTheScreen_leavesNoNegativeRegions() {
        val p = panes(frozenCols = 200, frozenRows = 200)
        val regions = regionsOf(p, Viewport(p.minScrollX, p.minScrollY, 1f))

        for (region in regions) {
            assertTrue("${region.pane} has negative width", region.right >= region.left)
            assertTrue("${region.pane} has negative height", region.bottom >= region.top)
        }
        assertTrue("the body has nothing left to draw", !regions.pane(Pane.BODY)!!.isVisible)
    }

    /**
     * The very first frame of a frozen sheet is drawn before the renderer has
     * published its [com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds], so the
     * state still holds `scroll = 0`. Taken literally that would draw the frozen
     * columns a second time inside the body — for one frame, but visibly. The
     * regions clamp for themselves instead of trusting the caller.
     */
    @Test
    fun anUnclampedViewport_stillStartsTheBodyPastTheFrozenStrips() {
        val p = panes(frozenCols = 1, frozenRows = 2)
        val unclamped = Viewport(scrollX = 0f, scrollY = 0f, zoom = 1f)
        val regions = regionsOf(p, unclamped)
        val body = regions.pane(Pane.BODY)!!

        assertEquals("the body reads from the frozen extent", p.minScrollX, body.viewport.scrollX, EPSILON)
        assertEquals(p.minScrollY, body.viewport.scrollY, EPSILON)
        assertTrue("and so starts past the frozen columns", body.firstColumn >= 1)
        assertTrue("and past the frozen rows", body.firstRow >= 2)

        // The frozen regions still read from the true origin.
        assertEquals(0f, regions.pane(Pane.CORNER)!!.viewport.scrollX, EPSILON)
        assertEquals(0f, regions.pane(Pane.CORNER)!!.viewport.scrollY, EPSILON)
    }

    /** With nothing frozen the clamp is a no-op, so scroll 0 stays scroll 0. */
    @Test
    fun anUnfrozenSheet_isNotClamped() {
        val body = regionsOf(panes(0, 0), Viewport(0f, 0f, 1f)).single()
        assertEquals(0f, body.viewport.scrollX, EPSILON)
        assertEquals(0, body.firstColumn)
        assertEquals(0, body.firstRow)
    }

    @Test
    fun theScrollFloorIsTheFrozenExtent() {
        val g = geometry(columnWidths = mapOf(0 to 22.5), rowHeights = mapOf(0 to 31.0, 1 to 8.0))
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)

        assertEquals(g.columnOffset(1), p.minScrollX, EPSILON)
        assertEquals(g.rowOffset(2), p.minScrollY, EPSILON)
    }

    // --- hit-testing across the four regions (T29) ---
    //
    // This is where frozen panes bite. Each region has a different origin and a
    // viewport with its frozen axes zeroed, so the same pixel offset means a
    // different cell depending on which region it lands in. A hit-test that
    // forgets that silently selects the wrong cell — the failure looks like a
    // rendering bug and is not one.

    /**
     * The set-up the device check uses: one frozen column, two frozen rows, and
     * the body scrolled well past both, so every region shows different cells.
     */
    private fun frozenAndScrolled(): Pair<PaneRegions, List<PaneRegion>> {
        val g = geometry(
            columnWidths = (0..9).associateWith { 10.0 },
            rowHeights = (0..29).associateWith { 20.0 },
        )
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)
        // Scrolled to column 4 / row 8: the body starts there while the frozen
        // strips still show columns 0 and rows 0-1.
        val viewport = Viewport(
            scrollX = g.columnOffset(4),
            scrollY = g.rowOffset(8),
            zoom = 1f,
        )
        return p to regionsOf(p, viewport)
    }

    @Test
    fun aTapInEachRegion_resolvesToThatRegionsCell() {
        val (_, regions) = frozenAndScrolled()
        val g = geometry(
            columnWidths = (0..9).associateWith { 10.0 },
            rowHeights = (0..29).associateWith { 20.0 },
        )

        // One pixel inside each region's top-left corner. The four answers are
        // all different, and that difference is the whole point: the corner shows
        // the sheet's origin while the body shows where it has scrolled to.
        val corner = regions.pane(Pane.CORNER)!!
        assertEquals(
            "frozen corner shows A1",
            CellRef(row = 0, col = 0),
            regions.cellAt(corner.left + 1f, corner.top + 1f, g),
        )

        val top = regions.pane(Pane.TOP)!!
        assertEquals(
            "frozen rows, scrolled columns",
            CellRef(row = 0, col = 4),
            regions.cellAt(top.left + 1f, top.top + 1f, g),
        )

        val left = regions.pane(Pane.LEFT)!!
        assertEquals(
            "frozen columns, scrolled rows",
            CellRef(row = 8, col = 0),
            regions.cellAt(left.left + 1f, left.top + 1f, g),
        )

        val body = regions.pane(Pane.BODY)!!
        assertEquals(
            "scrolled on both axes",
            CellRef(row = 8, col = 4),
            regions.cellAt(body.left + 1f, body.top + 1f, g),
        )
    }

    /**
     * The same screen pixel, read through the region it is actually in.
     *
     * If hit-testing ignored the regions and used the body's viewport
     * everywhere, the corner would answer with the body's cell. This asserts
     * they differ, so that mistake cannot pass.
     */
    @Test
    fun theSamePixelOffsetMeansDifferentCellsInDifferentRegions() {
        val (_, regions) = frozenAndScrolled()
        val g = geometry(
            columnWidths = (0..9).associateWith { 10.0 },
            rowHeights = (0..29).associateWith { 20.0 },
        )
        val corner = regions.cellAt(originX + 1f, originY + 1f, g)
        val body = regions.pane(Pane.BODY)!!
        val bodyCell = regions.cellAt(body.left + 1f, body.top + 1f, g)

        assertNotNull(corner)
        assertNotNull(bodyCell)
        assertTrue("frozen corner must not resolve like the body", corner != bodyCell)
    }

    @Test
    fun everyRegionIsReachable_soNoTapFallsBetweenThem() {
        val (_, regions) = frozenAndScrolled()
        val g = geometry(
            columnWidths = (0..9).associateWith { 10.0 },
            rowHeights = (0..29).associateWith { 20.0 },
        )
        // The regions tile the grid area, so sweeping it must never miss. A gap
        // would be a dead strip the user can tap with nothing happening.
        var x = originX
        while (x < width) {
            var y = originY
            while (y < height) {
                assertNotNull("no region owns ($x, $y)", regions.firstOrNull { it.contains(x, y) })
                y += 137f
            }
            x += 91f
        }
    }

    @Test
    fun aTapOnTheHeaderStrips_hitsNoRegion() {
        val (_, regions) = frozenAndScrolled()
        val g = geometry(columnWidths = (0..9).associateWith { 10.0 })
        // Above and left of the grid area is chrome, not cells.
        assertNull(regions.cellAt(originX - 1f, originY + 10f, g))
        assertNull(regions.cellAt(originX + 10f, originY - 1f, g))
    }

    @Test
    fun hitTestingHoldsAtEveryZoom() {
        val g = geometry(
            columnWidths = (0..9).associateWith { 10.0 },
            rowHeights = (0..29).associateWith { 20.0 },
        )
        val p = panes(frozenCols = 1, frozenRows = 2, geometry = g)
        for (zoom in ZOOMS) {
            val viewport = Viewport(p.minScrollX, p.minScrollY, zoom)
            val regions = regionsOf(p, viewport)
            val corner = regions.pane(Pane.CORNER)!!
            assertEquals(
                "at zoom $zoom the frozen corner still starts at A1",
                CellRef(row = 0, col = 0),
                regions.cellAt(corner.left + 1f, corner.top + 1f, g),
            )
        }
    }

    private companion object {
        const val EPSILON = 0.01f

        /** Sub-pixel: the seam and the first cell may differ by float noise only. */
        const val SUBPIXEL = 0.001f

        /** The zoom range the UI allows, plus awkward values between the stops. */
        val ZOOMS = floatArrayOf(0.5f, 0.73f, 1f, 1.37f, 2f, 2.5f, 3f)
    }
}
