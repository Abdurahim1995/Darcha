package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.ScrollBounds
import com.tikoncha.darcha.feature.viewer.mvi.Viewport
import com.tikoncha.darcha.model.CellRange

/**
 * Where the scroll must go for one axis so that `[start, end]` is visible
 * inside a body window `extent` long, with [margin] of clear space at each end.
 *
 * Pure 1-D arithmetic, and the whole of the hard part. Both axes call it; the
 * frozen strips are already accounted for by the time they do, because they only
 * ever shrink [extent].
 *
 * The rules, in the order they resolve:
 *
 * 1. **Already comfortably visible → do not move.** Scrolling when the target
 *    is on screen makes stepping through matches feel like the sheet is
 *    fighting the reader.
 * 2. **Too big to fit → show the start.** A merged range wider than the window
 *    cannot be framed, and its anchor is the end that carries the value.
 * 3. **Otherwise → the smallest movement** that satisfies the margin, so the
 *    target enters from whichever edge it was outside.
 *
 * The result is finally clamped to `[min, max]`. That clamp can eat the margin —
 * the first unfrozen column has nowhere further left to go — but it cannot hide
 * the target: `min` is the content coordinate where the body begins, and the
 * target is at or past it, so `scroll <= start` survives every clamp. Visible
 * without its margin, never invisible.
 */
internal fun solveAxisScroll(
    start: Float,
    end: Float,
    current: Float,
    extent: Float,
    margin: Float,
    min: Float,
    max: Float,
): Float {
    // A window with no room is not a window; nothing can be framed inside it.
    if (extent <= 0f) return current.coerceIn(min, maxOf(min, max))

    // Never demand more margin than the window can give on both sides at once.
    val gap = margin.coerceAtMost(extent / 4f)

    // Scroll must be at most this for the start to clear the leading margin...
    val latest = start - gap
    // ...and at least this for the end to clear the trailing one.
    val earliest = end - extent + gap

    val target = when {
        // Rule 2: the span cannot satisfy both, so frame its start.
        earliest > latest -> latest
        // Rule 1: already inside the comfortable window.
        current in earliest..latest -> current
        // Rule 3: enter from the nearer edge.
        current < earliest -> earliest
        else -> latest
    }
    return target.coerceIn(min, maxOf(min, max))
}

/**
 * The viewport that brings [target] into view (T31).
 *
 * ## Why this cannot hide the target under a frozen strip
 *
 * The trap this function exists for: frozen rows and columns are drawn **over**
 * the body (TECH_SPEC §9, T19), so a scroll computed against the whole canvas
 * can park a cell underneath one — on screen by the arithmetic, invisible to the
 * reader, and with no error anywhere to say so.
 *
 * There is no guard against that here, because there is no code path that could
 * produce it. Everything is solved in the **body's own content window**,
 * `[scroll, scroll + extent]`, and "visible" is defined as `start >= scroll +
 * margin`. Since `scroll` is what is being solved for, the target's content
 * coordinate always ends up at or past where the body begins drawing. The frozen
 * strips enter the calculation in exactly one place — they shorten `extent` —
 * which is the same "one number per axis" property that makes [PaneRegions]'
 * seams reliable.
 *
 * The clamp is the one place the two forces meet, and it resolves provably: the
 * floor is `minScroll`, the content coordinate where the body starts, and a
 * non-frozen target is by definition at or past it. So clamping can cost the
 * target its margin but never its visibility.
 *
 * ## Frozen targets do not scroll
 *
 * A cell inside the frozen band is on screen by construction — that is what
 * freezing means — so its axis is left alone. A cell frozen on one axis only
 * moves on the other.
 *
 * @param target the cell, or the merged range containing it. A range wider or
 *   taller than the window is framed from its start, which is its anchor.
 * @param originX left edge of the grid area — right of the row header strip.
 * @param originY top edge of the grid area — below the column header strip.
 */
internal fun PaneRegions.scrollToShow(
    target: CellRange,
    viewport: Viewport,
    bounds: ScrollBounds,
    originX: Float,
    originY: Float,
    width: Float,
    height: Float,
): Viewport {
    val zoom = viewport.zoom.coerceAtLeast(GridGeometry.MIN_EFFECTIVE_ZOOM)

    // Exactly as PaneRegions.regions() computes them, so the window this solves
    // in is the window that gets drawn.
    val splitX = (originX + frozenWidth(viewport)).coerceIn(originX, maxOf(originX, width))
    val splitY = (originY + frozenHeight(viewport)).coerceIn(originY, maxOf(originY, height))

    // The body in content units. Screen space minus the frozen strips and the
    // header chrome, divided by zoom once, at the edge (TECH_SPEC §9.2).
    val extentX = (width - splitX) / zoom
    val extentY = (height - splitY) / zoom

    val scrollX = if (isColumnFrozen(target.startCol)) {
        viewport.scrollX
    } else {
        solveAxisScroll(
            start = geometry.columnOffset(target.startCol),
            end = geometry.columnOffset(target.endCol) + geometry.columnWidth(target.endCol),
            current = viewport.scrollX,
            extent = extentX,
            margin = geometry.defaultColumnWidth / 2f,
            min = bounds.minScrollX,
            max = bounds.maxScrollX,
        )
    }

    val scrollY = if (isRowFrozen(target.startRow)) {
        viewport.scrollY
    } else {
        solveAxisScroll(
            start = geometry.rowOffset(target.startRow),
            end = geometry.rowOffset(target.endRow) + geometry.rowHeight(target.endRow),
            current = viewport.scrollY,
            extent = extentY,
            margin = geometry.defaultRowHeight / 2f,
            min = bounds.minScrollY,
            max = bounds.maxScrollY,
        )
    }

    return if (scrollX == viewport.scrollX && scrollY == viewport.scrollY) {
        viewport
    } else {
        viewport.copy(scrollX = scrollX, scrollY = scrollY)
    }
}
