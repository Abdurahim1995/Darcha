package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.Row
import com.tikoncha.darcha.model.SheetLayout

/**
 * A batch of worksheet rows delivered during progressive loading (TECH_SPEC §7).
 *
 * Each chunk holds the rows parsed since the previous chunk (a delta), keyed by
 * 0-based row index. Concatenating every chunk's [rows] reproduces the full
 * sheet, which lets the UI render rows as they stream in.
 *
 * A chunk also carries the [layout] those rows are sized by, so the renderer can
 * place them correctly the *first* time it draws them instead of laying the
 * sheet out again when the parse ends (T15.6).
 *
 * @property rows the rows parsed in this chunk.
 * @property rowsSoFar cumulative count of populated rows emitted so far.
 * @property layout what it takes to size these rows. Its parts have deliberately
 *   different lifetimes, because the XML delivers them at different moments:
 *   - [SheetLayout.columnWidths], [SheetLayout.defaultColWidth] and
 *     [SheetLayout.defaultRowHeight] are **complete from the very first chunk**.
 *     `<cols>` and `<sheetFormatPr>` precede `<sheetData>` in the schema, so the
 *     column axis is final before any row exists; every chunk of a sheet shares
 *     the same instance.
 *   - [SheetLayout.rowHeights] is **a delta, exactly like [rows]** — only the
 *     heights of this chunk's rows. A row's height arrives with the row itself,
 *     so a chunk can always be drawn at its true height and a later row never
 *     shifts one already on screen. Merge chunk layouts the way you merge chunk
 *     rows: `putAll`.
 *   - [SheetLayout.merges] and [SheetLayout.frozenPanes] are **always empty**:
 *     `<mergeCells>` follows `<sheetData>`, so they are known only once the part
 *     is fully read and arrive with the returned
 *     [com.tikoncha.darcha.model.Worksheet].
 *
 *   One gap, by design: a *trailing* row carrying a height but no cells emits no
 *   chunk, so its height reaches only the final worksheet. Such rows are empty
 *   and below everything drawn, so nothing moves.
 */
public data class RowsChunk(
    public val rows: Map<Int, Row>,
    public val rowsSoFar: Int,
    public val layout: SheetLayout = SheetLayout.EMPTY,
)
