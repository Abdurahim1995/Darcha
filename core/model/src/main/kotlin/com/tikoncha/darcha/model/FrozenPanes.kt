package com.tikoncha.darcha.model

/**
 * Frozen row/column counts for a worksheet (TECH_SPEC §9). A sheet with the
 * first row and first column frozen is `FrozenPanes(frozenCols = 1, frozenRows = 1)`.
 *
 * @property frozenCols number of frozen leading columns (OOXML `pane/@xSplit`).
 * @property frozenRows number of frozen leading rows (OOXML `pane/@ySplit`).
 */
public data class FrozenPanes(
    public val frozenCols: Int,
    public val frozenRows: Int,
) {
    /** Whether any rows or columns are frozen. */
    public val isFrozen: Boolean get() = frozenCols > 0 || frozenRows > 0

    public companion object {
        /** Nothing frozen. */
        public val NONE: FrozenPanes = FrozenPanes(frozenCols = 0, frozenRows = 0)
    }
}
