package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.StringTable
import com.tikoncha.darcha.model.StyleTable

/**
 * Everything the renderer needs to draw one worksheet — an immutable snapshot,
 * so Compose can treat it as a stable input (TECH_SPEC §8).
 *
 * Cells keep their **raw** values: a shared-string cell holds an index, not the
 * text. Display strings are resolved lazily while drawing and cached there, as
 * §8 requires, rather than being materialized up front for a sheet that may be
 * a million rows long.
 *
 * The workbook-wide tables travel with the sheet because a cell is meaningless
 * without them: a value is an index or a bare number until [sharedStrings] and
 * [styles] say what it is (T17).
 *
 * @property data the sparse cells.
 * @property layout column widths, row heights, merges and frozen panes.
 * @property sharedStrings the workbook's shared strings, for resolving
 *   [com.tikoncha.darcha.model.CellValue.SharedText].
 * @property styles the workbook's style table; a cell's style id indexes it.
 * @property date1904 the workbook's epoch flag, which decides what a date
 *   serial means.
 */
public data class SheetSnapshot(
    public val data: SheetData,
    public val layout: SheetLayout,
    public val sharedStrings: StringTable,
    public val styles: StyleTable = StyleTable.EMPTY,
    public val date1904: Boolean = false,
) {
    public companion object {
        /** An empty sheet, for previews and tests. */
        public val EMPTY: SheetSnapshot = SheetSnapshot(
            data = SheetData.EMPTY,
            layout = SheetLayout.EMPTY,
            sharedStrings = StringTable.EMPTY,
            styles = StyleTable.EMPTY,
        )
    }
}
