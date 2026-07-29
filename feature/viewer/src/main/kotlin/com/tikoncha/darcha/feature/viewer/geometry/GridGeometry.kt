package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.DEFAULT_MAX_DIGIT_WIDTH
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.columnWidthToPixels

/**
 * The block of cells currently touching the viewport, in 0-based inclusive
 * coordinates. Edge entries that are only partly on screen are included — they
 * still have pixels to draw.
 */
public data class VisibleRange(
    public val firstRow: Int,
    public val lastRow: Int,
    public val firstColumn: Int,
    public val lastColumn: Int,
) {
    /** Number of rows in the range. */
    public val rowCount: Int get() = lastRow - firstRow + 1

    /** Number of columns in the range. */
    public val columnCount: Int get() = lastColumn - firstColumn + 1

    /** Cells the renderer would visit for this range. */
    public val cellCount: Int get() = rowCount * columnCount
}

/**
 * Maps between grid coordinates and pixels (TECH_SPEC §9). Pure Kotlin — no
 * Compose, no Android — so it is exhaustively unit testable and reusable.
 *
 * **Coordinate spaces.** Sizes and offsets are *content pixels*: the grid at
 * zoom 1. [Viewport.scrollX] / [Viewport.scrollY] are content pixels too, so
 * geometry stays zoom-independent and zoom is applied once, at the edge:
 *
 * ```
 * screen = (content - scroll) * zoom
 * ```
 *
 * Nothing is stored per row or per column — see [Axis] — so a 1,048,576 × 16,384
 * sheet costs no more than a small one.
 *
 * @param layout the sheet's custom sizes and defaults.
 * @param maxDigitWidth the default font's max digit width, for the column-width
 *   conversion (TECH_SPEC §7 traps).
 * @param pointToPixel scale from OOXML points (row heights) to pixels. Defaults
 *   to the 96 DPI ratio `96/72`, matching the DPI basis [columnWidthToPixels]
 *   assumes; the renderer can pass the real display density instead.
 */
public class GridGeometry(
    layout: SheetLayout,
    maxDigitWidth: Int = DEFAULT_MAX_DIGIT_WIDTH,
    pointToPixel: Float = POINTS_TO_PIXELS_96DPI,
    rowCount: Int = MAX_ROWS,
    columnCount: Int = MAX_COLUMNS,
) {
    private val columns = Axis(
        defaultSize = columnWidthToPixels(layout.defaultColWidth, maxDigitWidth).toFloat(),
        customSizes = layout.columnWidths.mapValues {
            columnWidthToPixels(it.value, maxDigitWidth).toFloat()
        },
        count = columnCount,
    )

    private val rows = Axis(
        defaultSize = (layout.defaultRowHeight * pointToPixel).toFloat(),
        customSizes = layout.rowHeights.mapValues { (it.value * pointToPixel).toFloat() },
        count = rowCount,
    )

    /** Number of addressable rows. */
    public val rowCount: Int get() = rows.count

    /** Number of addressable columns. */
    public val columnCount: Int get() = columns.count

    /** Full grid width in content pixels. */
    public val contentWidth: Float get() = columns.totalSize

    /** Full grid height in content pixels. */
    public val contentHeight: Float get() = rows.totalSize

    /** Left edge of [column] in content pixels. */
    public fun columnOffset(column: Int): Float = columns.offsetOf(column)

    /** Width of [column] in content pixels. */
    public fun columnWidth(column: Int): Float = columns.sizeOf(column)

    /** Top edge of [row] in content pixels. */
    public fun rowOffset(row: Int): Float = rows.offsetOf(row)

    /** Height of [row] in content pixels. */
    public fun rowHeight(row: Int): Float = rows.sizeOf(row)

    /**
     * The rows and columns touching a [canvasWidth] × [canvasHeight] canvas at
     * [viewport], edges included.
     *
     * The canvas is measured in screen pixels, so at zoom 2 it covers half as
     * much content — which is why the visible span is divided by the zoom.
     */
    public fun visibleRange(viewport: Viewport, canvasWidth: Float, canvasHeight: Float): VisibleRange {
        val zoom = viewport.zoom.coerceAtLeast(MIN_EFFECTIVE_ZOOM)
        val left = viewport.scrollX
        val top = viewport.scrollY
        val right = left + canvasWidth / zoom
        val bottom = top + canvasHeight / zoom

        return VisibleRange(
            firstRow = rows.indexAt(top),
            lastRow = maxOf(rows.indexAt(top), rows.lastIndexBefore(bottom)),
            firstColumn = columns.indexAt(left),
            lastColumn = maxOf(columns.indexAt(left), columns.lastIndexBefore(right)),
        )
    }

    /**
     * The cell under the screen point ([x], [y]) for [viewport], or `null` if the
     * point falls past the end of the grid.
     */
    public fun cellAt(x: Float, y: Float, viewport: Viewport): CellRef? {
        val zoom = viewport.zoom.coerceAtLeast(MIN_EFFECTIVE_ZOOM)
        val contentX = viewport.scrollX + x / zoom
        val contentY = viewport.scrollY + y / zoom
        if (contentX < 0f || contentY < 0f) return null
        if (contentX >= contentWidth || contentY >= contentHeight) return null
        return CellRef(row = rows.indexAt(contentY), col = columns.indexAt(contentX))
    }

    /** Screen x of [column]'s left edge for [viewport]. */
    public fun screenXOf(column: Int, viewport: Viewport): Float =
        (columns.offsetOf(column) - viewport.scrollX) * viewport.zoom

    /** Screen y of [row]'s top edge for [viewport]. */
    public fun screenYOf(row: Int, viewport: Viewport): Float =
        (rows.offsetOf(row) - viewport.scrollY) * viewport.zoom

    /** Screen width of [column] at [viewport]'s zoom. */
    public fun screenWidthOf(column: Int, viewport: Viewport): Float =
        columns.sizeOf(column) * viewport.zoom

    /** Screen height of [row] at [viewport]'s zoom. */
    public fun screenHeightOf(row: Int, viewport: Viewport): Float =
        rows.sizeOf(row) * viewport.zoom

    public companion object {
        /** Excel's row limit, and ours. */
        public const val MAX_ROWS: Int = 1_048_576

        /** Excel's column limit (`XFD`), and ours. */
        public const val MAX_COLUMNS: Int = 16_384

        /** Points to pixels at 96 DPI — the basis the width formula assumes. */
        public const val POINTS_TO_PIXELS_96DPI: Float = 96f / 72f

        /** Floor for zoom, so a zero or negative value can never divide. */
        private const val MIN_EFFECTIVE_ZOOM = 0.01f
    }
}
