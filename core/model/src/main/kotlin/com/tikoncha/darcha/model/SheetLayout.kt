package com.tikoncha.darcha.model

/**
 * Per-worksheet layout inputs for rendering (TECH_SPEC §9): custom column widths
 * and row heights, sheet defaults, merged ranges, and frozen panes.
 *
 * @property columnWidths custom column widths in character units, keyed by
 *   0-based column index. Columns absent here use [defaultColWidth].
 * @property rowHeights custom row heights in points, keyed by 0-based row index.
 *   Rows absent here use [defaultRowHeight].
 * @property defaultColWidth default column width, in character units.
 * @property defaultRowHeight default row height, in points.
 * @property merges merged cell ranges.
 * @property frozenPanes frozen row/column configuration.
 */
public data class SheetLayout(
    public val columnWidths: Map<Int, Double>,
    public val rowHeights: Map<Int, Double>,
    public val defaultColWidth: Double,
    public val defaultRowHeight: Double,
    public val merges: List<CellRange>,
    public val frozenPanes: FrozenPanes,
) {
    public companion object {
        /** OOXML default column width (characters) when the sheet declares none. */
        public const val DEFAULT_COL_WIDTH: Double = 8.43

        /** OOXML default row height (points) when the sheet declares none. */
        public const val DEFAULT_ROW_HEIGHT: Double = 15.0

        /** Layout with no custom sizing, merges, or freezing. */
        public val EMPTY: SheetLayout = SheetLayout(
            columnWidths = emptyMap(),
            rowHeights = emptyMap(),
            defaultColWidth = DEFAULT_COL_WIDTH,
            defaultRowHeight = DEFAULT_ROW_HEIGHT,
            merges = emptyList(),
            frozenPanes = FrozenPanes.NONE,
        )
    }
}

/**
 * A fully parsed worksheet: its sparse cell [data] and its [layout]
 * (TECH_SPEC §7 step 5, §9).
 */
public data class Worksheet(
    public val data: SheetData,
    public val layout: SheetLayout,
)
