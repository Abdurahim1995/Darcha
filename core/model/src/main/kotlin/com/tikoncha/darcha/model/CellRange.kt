package com.tikoncha.darcha.model

/**
 * A rectangular block of cells in 0-based inclusive coordinates (TECH_SPEC §9),
 * used for merged ranges. Start coordinates are always ≤ end coordinates.
 *
 * @property startRow top row (inclusive).
 * @property startCol left column (inclusive).
 * @property endRow bottom row (inclusive).
 * @property endCol right column (inclusive).
 */
public data class CellRange(
    public val startRow: Int,
    public val startCol: Int,
    public val endRow: Int,
    public val endCol: Int,
) {
    /** Number of rows the range spans. */
    public val rowCount: Int get() = endRow - startRow + 1

    /** Number of columns the range spans. */
    public val colCount: Int get() = endCol - startCol + 1

    /** Whether ([row], [col]) lies within this range. */
    public fun contains(row: Int, col: Int): Boolean =
        row in startRow..endRow && col in startCol..endCol
}
