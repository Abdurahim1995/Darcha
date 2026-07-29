package com.tikoncha.darcha.feature.viewer.data

import com.tikoncha.darcha.model.SheetData
import com.tikoncha.darcha.model.SheetLayout
import com.tikoncha.darcha.model.StringTable

/**
 * Everything the renderer needs to draw one worksheet — an immutable snapshot,
 * so Compose can treat it as a stable input (TECH_SPEC §8).
 *
 * Cells keep their **raw** values: a shared-string cell holds an index, not the
 * text. Display strings are resolved lazily while drawing and cached there, as
 * §8 requires, rather than being materialized up front for a sheet that may be
 * a million rows long.
 *
 * @property data the sparse cells.
 * @property layout column widths, row heights, merges and frozen panes.
 * @property sharedStrings the workbook's shared strings, for resolving
 *   [com.tikoncha.darcha.model.CellValue.SharedText].
 */
public data class SheetSnapshot(
    public val data: SheetData,
    public val layout: SheetLayout,
    public val sharedStrings: StringTable,
) {
    public companion object {
        /** An empty sheet, for previews and tests. */
        public val EMPTY: SheetSnapshot = SheetSnapshot(
            data = SheetData.EMPTY,
            layout = SheetLayout.EMPTY,
            sharedStrings = StringTable.EMPTY,
        )
    }
}
