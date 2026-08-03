package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.FrozenPanes

/**
 * Which of the four panes a region is (TECH_SPEC §9 — "four clipped regions with
 * translated origins").
 */
internal enum class Pane {
    /** Frozen on both axes: the block above and left of both splits. */
    CORNER,

    /** Frozen rows, scrolling columns: the strip along the top. */
    TOP,

    /** Frozen columns, scrolling rows: the strip down the left. */
    LEFT,

    /** Frozen on neither axis: everything else. */
    BODY,
}

/**
 * One drawable region: where to clip, where its content origin sits, which
 * viewport to read it with, and which cells fall inside it.
 *
 * The [viewport] is the caller's with the **frozen axes zeroed**, which is the
 * whole trick: a region is drawn by exactly the same code as an unfrozen grid,
 * just with a scroll that does not move and an origin pushed past the frozen
 * strips. Nothing else in the renderer needs to know about freezing.
 *
 * @property originX screen x where this region's content coordinate
 *   `viewport.scrollX` lands.
 * @property originY screen y where `viewport.scrollY` lands.
 */
internal data class PaneRegion(
    val pane: Pane,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val originX: Float,
    val originY: Float,
    val viewport: Viewport,
    val firstRow: Int,
    val lastRow: Int,
    val firstColumn: Int,
    val lastColumn: Int,
) {
    /** Whether the region has any pixels to draw. */
    val isVisible: Boolean
        get() = right > left && bottom > top && lastRow >= firstRow && lastColumn >= firstColumn

    /** Cells the renderer visits for this region. */
    val cellCount: Int
        get() = (lastRow - firstRow + 1) * (lastColumn - firstColumn + 1)

    /**
     * Whether the screen point ([x], [y]) falls in this region.
     *
     * Half-open on the right and bottom, matching how [PaneRegions.regions]
     * builds them, so the four regions tile the grid area exactly: a point on a
     * seam belongs to precisely one of them and a tap can never be ambiguous.
     */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

/**
 * The cell under the screen point ([x], [y]), or `null` if the point misses the
 * grid (T29).
 *
 * **This is where frozen panes bite.** Each region has its own origin and its own
 * viewport with the frozen axes zeroed, so a tap in the frozen corner and the
 * same pixel offset in the body are different cells. Translating into the
 * region's own space first is what makes the four cases one case: after
 * subtracting the origin, [GridGeometry.cellAt] is the ordinary unfrozen
 * calculation, which is the same property the drawing code relies on.
 *
 * Iterated in reverse — frozen regions first — to mirror draw order, so the
 * answer matches what is visibly on top even if the rectangles ever stop tiling
 * perfectly.
 */
internal fun List<PaneRegion>.cellAt(x: Float, y: Float, geometry: GridGeometry): CellRef? {
    for (i in indices.reversed()) {
        val region = this[i]
        if (!region.contains(x, y)) continue
        return geometry.cellAt(x - region.originX, y - region.originY, region.viewport)
    }
    return null
}

/**
 * Splits the grid area into frozen regions (TECH_SPEC §9).
 *
 * ## Where the seams come from, and why there are none
 *
 * Every boundary in this file is derived from **one** number per axis — the
 * frozen extent, `spanWidthOf(0, frozenCols - 1)` — and every region that
 * touches that boundary is positioned from the same value. The corner's right
 * edge, the top strip's origin, the body's left clip and the separator line are
 * all literally the same `Float`, so they cannot disagree at any zoom: there is
 * no rounding step between them to disagree *in*.
 *
 * The regions are half-open and adjacent (`corner.right == top.left`), so they
 * tile the area exactly — no gap, no overlap.
 */
internal class PaneRegions(
    internal val geometry: GridGeometry,
    private val panes: FrozenPanes,
) {

    /** Frozen columns, clamped to something the grid can actually hold. */
    private val frozenCols: Int = panes.frozenCols.coerceIn(0, geometry.columnCount)

    /** Frozen rows, clamped likewise. */
    private val frozenRows: Int = panes.frozenRows.coerceIn(0, geometry.rowCount)

    /** Whether anything is frozen at all — the common case is `false`. */
    val isFrozen: Boolean get() = frozenCols > 0 || frozenRows > 0

    /**
     * Whether [column] is in the frozen band, and therefore on screen already.
     *
     * Scrolling cannot move it and does not need to: that is what freezing
     * means. [scrollToShow] leans on this rather than discovering it.
     */
    fun isColumnFrozen(column: Int): Boolean = column < frozenCols

    /** Whether [row] is in the frozen band — see [isColumnFrozen]. */
    fun isRowFrozen(row: Int): Boolean = row < frozenRows

    /**
     * Content x the scrolling region starts at: the left edge of the first
     * unfrozen column. Scroll must not go below this, or the body would draw the
     * frozen columns a second time.
     */
    val minScrollX: Float get() = if (frozenCols > 0) geometry.columnOffset(frozenCols) else 0f

    /** Content y the scrolling region starts at. */
    val minScrollY: Float get() = if (frozenRows > 0) geometry.rowOffset(frozenRows) else 0f

    /** Screen width of the frozen columns at [viewport]'s zoom. */
    fun frozenWidth(viewport: Viewport): Float =
        if (frozenCols > 0) geometry.spanWidthOf(0, frozenCols - 1, viewport) else 0f

    /** Screen height of the frozen rows at [viewport]'s zoom. */
    fun frozenHeight(viewport: Viewport): Float =
        if (frozenRows > 0) geometry.spanHeightOf(0, frozenRows - 1, viewport) else 0f

    /**
     * The regions covering the grid area, in draw order (frozen last, so a
     * frozen strip is never painted over by the body it overlaps at the edge).
     *
     * @param viewport the sheet's viewport. Its scroll is clamped to the frozen
     *   extent here rather than trusted: [ScrollBounds] carries the same floor,
     *   but it is published by the renderer *after* the first composition, so on
     *   the very first frame of a frozen sheet the state has not been clamped
     *   yet. Clamping in both places costs one comparison and removes a frame
     *   where the body would redraw the frozen columns.
     * @param originX left edge of the grid area on screen — right of the row
     *   header strip.
     * @param originY top edge of the grid area — below the column header strip.
     * @param width total canvas width.
     * @param height total canvas height.
     */
    fun regions(
        viewport: Viewport,
        originX: Float,
        originY: Float,
        width: Float,
        height: Float,
    ): List<PaneRegion> {
        // A frozen strip wider than the screen would leave the body a negative
        // rectangle; clamping keeps every region well-formed instead.
        val splitX = (originX + frozenWidth(viewport)).coerceIn(originX, width)
        val splitY = (originY + frozenHeight(viewport)).coerceIn(originY, height)

        // The scrolling region begins past the frozen strips, whatever the state
        // says — see the parameter doc.
        val scrolling = viewport.copy(
            scrollX = viewport.scrollX.coerceAtLeast(minScrollX),
            scrollY = viewport.scrollY.coerceAtLeast(minScrollY),
        )

        // One range for the scrolling area serves every region that scrolls.
        val scrolled = geometry.visibleRange(scrolling, width - splitX, height - splitY)
        val frozenViewport = viewport.copy(scrollX = 0f, scrollY = 0f)

        val regions = ArrayList<PaneRegion>(REGION_COUNT)

        // Body first: the frozen strips draw over it where they meet.
        regions.add(
            PaneRegion(
                pane = Pane.BODY,
                left = splitX, top = splitY, right = width, bottom = height,
                originX = splitX, originY = splitY,
                viewport = scrolling,
                firstRow = scrolled.firstRow, lastRow = scrolled.lastRow,
                firstColumn = scrolled.firstColumn, lastColumn = scrolled.lastColumn,
            ),
        )
        if (frozenRows > 0) {
            regions.add(
                PaneRegion(
                    pane = Pane.TOP,
                    left = splitX, top = originY, right = width, bottom = splitY,
                    originX = splitX, originY = originY,
                    viewport = scrolling.copy(scrollY = 0f),
                    firstRow = 0, lastRow = frozenRows - 1,
                    firstColumn = scrolled.firstColumn, lastColumn = scrolled.lastColumn,
                ),
            )
        }
        if (frozenCols > 0) {
            regions.add(
                PaneRegion(
                    pane = Pane.LEFT,
                    left = originX, top = splitY, right = splitX, bottom = height,
                    originX = originX, originY = splitY,
                    viewport = scrolling.copy(scrollX = 0f),
                    firstRow = scrolled.firstRow, lastRow = scrolled.lastRow,
                    firstColumn = 0, lastColumn = frozenCols - 1,
                ),
            )
        }
        if (frozenCols > 0 && frozenRows > 0) {
            regions.add(
                PaneRegion(
                    pane = Pane.CORNER,
                    left = originX, top = originY, right = splitX, bottom = splitY,
                    originX = originX, originY = originY,
                    viewport = frozenViewport,
                    firstRow = 0, lastRow = frozenRows - 1,
                    firstColumn = 0, lastColumn = frozenCols - 1,
                ),
            )
        }
        return regions
    }

    private companion object {
        const val REGION_COUNT = 4
    }
}
