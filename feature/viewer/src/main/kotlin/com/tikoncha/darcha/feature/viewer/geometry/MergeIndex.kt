package com.tikoncha.darcha.feature.viewer.geometry

import com.tikoncha.darcha.feature.viewer.mvi.CellRef
import com.tikoncha.darcha.model.CellRange

/**
 * The merged ranges of a sheet, in a form the draw loop can ask about per cell
 * (TECH_SPEC §9 — "drawn once at the anchor cell spanning the merged bounds;
 * covered cells are skipped").
 *
 * ## Why not a set of covered cells
 *
 * The obvious skip set — every covered `(row, col)` in a hash set — is a trap.
 * A single `A1:C1048576` merge is three cells wide and a million tall, so the
 * set would hold three million entries for one range. Ranges are stored as
 * ranges instead, and the lookup does the work.
 *
 * ## Why it is still cheap
 *
 * Ranges are held in parallel [IntArray]s sorted by start row, with a running
 * maximum of the end rows beside them. A lookup binary-searches to the last
 * range that starts at or before the row, then walks backwards — stopping as
 * soon as the running maximum proves no earlier range can reach that row. On the
 * sheets this was built for (a handful of header merges) the walk visits one or
 * two entries; it degrades gracefully rather than linearly.
 *
 * Everything is primitive arrays and index arithmetic: **a lookup allocates
 * nothing**, which matters because the draw loop calls it once per visible cell,
 * every frame (TECH_SPEC §8 — primitive arrays in hot paths).
 *
 * Instances are immutable and built once per sheet.
 */
internal class MergeIndex private constructor(
    private val startRows: IntArray,
    private val endRows: IntArray,
    private val startCols: IntArray,
    private val endCols: IntArray,
    private val maxEndRowUpTo: IntArray,
) {

    /** Number of merged ranges. */
    val size: Int get() = startRows.size

    /** Whether the sheet has no merges at all — the common case, worth a fast path. */
    val isEmpty: Boolean get() = startRows.isEmpty()

    /**
     * The index of the merge covering ([row], [col]), or [NONE].
     *
     * Overlapping merges are invalid in OOXML; if a file contains them anyway,
     * the one found first wins and the render stays deterministic.
     */
    fun indexOf(row: Int, col: Int): Int {
        if (startRows.isEmpty()) return NONE
        var i = lastStartingAtOrBefore(row)
        while (i >= 0) {
            // No range at or before i reaches this row, so neither will any earlier one.
            if (maxEndRowUpTo[i] < row) return NONE
            if (row <= endRows[i] && col >= startCols[i] && col <= endCols[i]) return i
            i--
        }
        return NONE
    }

    /** Whether ([row], [col]) is the top-left cell of merge [index]. */
    fun isAnchor(index: Int, row: Int, col: Int): Boolean =
        row == startRows[index] && col == startCols[index]

    /**
     * The cell that actually holds the value for ([row], [col]).
     *
     * A covered cell maps to its merge's anchor; every other cell maps to
     * itself. This is what a tap has to go through — tapping the right-hand half
     * of a merged title must select the title, not an empty cell (T20 wires the
     * gesture; the mapping lives here).
     */
    fun anchorOf(row: Int, col: Int): CellRef {
        val i = indexOf(row, col)
        return if (i == NONE) CellRef(row, col) else CellRef(startRows[i], startCols[i])
    }

    fun startRow(index: Int): Int = startRows[index]
    fun startCol(index: Int): Int = startCols[index]
    fun endRow(index: Int): Int = endRows[index]
    fun endCol(index: Int): Int = endCols[index]

    /**
     * Call [action] for every merge touching the rows `firstRow..lastRow` and
     * columns `firstColumn..lastColumn`, passing its index.
     *
     * Used to paint merged spans once per frame. It takes the range rather than
     * returning a list precisely so no frame allocates one.
     */
    inline fun forEachIntersecting(
        firstRow: Int,
        lastRow: Int,
        firstColumn: Int,
        lastColumn: Int,
        action: (index: Int) -> Unit,
    ) {
        for (i in 0 until size) {
            if (startRow(i) > lastRow || endRow(i) < firstRow) continue
            if (startCol(i) > lastColumn || endCol(i) < firstColumn) continue
            action(i)
        }
    }

    companion object {
        /** Returned by [indexOf] when a cell is in no merged range. */
        const val NONE: Int = -1

        /** A sheet with no merges. */
        val EMPTY: MergeIndex = MergeIndex(
            IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0),
        )

        /** Build an index over [ranges]; degenerate single-cell merges are dropped. */
        fun of(ranges: List<CellRange>): MergeIndex {
            // A 1x1 "merge" covers nothing and would only cost lookups.
            val useful = ranges.filter { it.rowCount > 1 || it.colCount > 1 }
            if (useful.isEmpty()) return EMPTY

            val sorted = useful.sortedBy { it.startRow }
            val n = sorted.size
            val startRows = IntArray(n)
            val endRows = IntArray(n)
            val startCols = IntArray(n)
            val endCols = IntArray(n)
            val maxEndRowUpTo = IntArray(n)
            var runningMax = Int.MIN_VALUE
            for (i in 0 until n) {
                val r = sorted[i]
                startRows[i] = r.startRow
                endRows[i] = r.endRow
                startCols[i] = r.startCol
                endCols[i] = r.endCol
                if (r.endRow > runningMax) runningMax = r.endRow
                maxEndRowUpTo[i] = runningMax
            }
            return MergeIndex(startRows, endRows, startCols, endCols, maxEndRowUpTo)
        }
    }

    /** Index of the last range whose start row is `<= row`, or -1. */
    private fun lastStartingAtOrBefore(row: Int): Int {
        var low = 0
        var high = startRows.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (startRows[mid] <= row) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}
