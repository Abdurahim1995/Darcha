package com.tikoncha.darcha.model

import java.util.Arrays

/**
 * One worksheet's cells in a sparse row (TECH_SPEC §8).
 *
 * [columns] is sorted ascending; [values] and [styleIds] are parallel to it —
 * cell `k` of the row is `(columns[k], values[k], styleIds[k])`. Empty cells are
 * never stored. Callers may read the arrays directly for hot-path iteration, or
 * use [valueAt] / [styleIdAt] for random access.
 *
 * @property columns the populated column indices, sorted ascending.
 * @property values the cell values, parallel to [columns].
 * @property styleIds the cell style ids (`cellXfs` index), parallel to [columns].
 */
public class Row(
    public val columns: IntArray,
    public val values: Array<CellValue>,
    public val styleIds: IntArray,
) {
    /** Number of populated cells in this row. */
    public val size: Int get() = columns.size

    /** The value at [column], or `null` if that column is empty in this row. */
    public fun valueAt(column: Int): CellValue? {
        val i = Arrays.binarySearch(columns, column)
        return if (i >= 0) values[i] else null
    }

    /** The style id at [column], or `null` if that column is empty in this row. */
    public fun styleIdAt(column: Int): Int? {
        val i = Arrays.binarySearch(columns, column)
        return if (i >= 0) styleIds[i] else null
    }
}

/**
 * A worksheet as a sparse map of row index → [Row] (TECH_SPEC §8). Row and
 * column indices are 0-based.
 *
 * @property rows populated rows, keyed by 0-based row index.
 */
public class SheetData(
    public val rows: Map<Int, Row>,
) {
    /** Total number of populated cells across all rows. */
    public val cellCount: Int get() = rows.values.sumOf { it.size }

    /** The [Row] at [row], or `null` if the row has no cells. */
    public fun row(row: Int): Row? = rows[row]

    /** The value at ([row], [column]), or `null` if that cell is empty. */
    public fun cellAt(row: Int, column: Int): CellValue? = rows[row]?.valueAt(column)

    /** The style id at ([row], [column]), or `null` if that cell is empty. */
    public fun styleIdAt(row: Int, column: Int): Int? = rows[row]?.styleIdAt(column)

    public companion object {
        /** An empty sheet. */
        public val EMPTY: SheetData = SheetData(emptyMap())
    }
}
