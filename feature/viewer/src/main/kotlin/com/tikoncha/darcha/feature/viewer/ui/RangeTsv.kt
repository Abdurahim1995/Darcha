package com.tikoncha.darcha.feature.viewer.ui

import com.tikoncha.darcha.feature.viewer.data.SheetSnapshot
import com.tikoncha.darcha.model.CellRange
import com.tikoncha.darcha.model.FormattedValueCache

/**
 * A selected range as tab-separated values, so it pastes into a spreadsheet as
 * **cells** rather than as one string in one cell (T34).
 *
 * ## The shape, decided rather than assumed
 *
 * - **Tab between columns, newline between rows.** What Excel, Google Sheets and
 *   LibreOffice all read from the clipboard.
 * - **An empty cell is an empty field.** Two tabs in a row. Dropping it would
 *   shift every value after it into the wrong column, which is the one failure
 *   that turns a paste into corrupt data rather than an inconvenience.
 * - **A merged cell contributes its value once, at its anchor**, and empty
 *   fields for every cell it covers. That is what the source file itself
 *   contains — the covered cells hold nothing — and it is what a spreadsheet
 *   produces when copying the same block, so the paste keeps the grid's shape.
 *   Nothing here re-merges: the destination gets a plain rectangle, with the
 *   value where the eye expects it.
 * - **The displayed text, not the raw value.** The same decision as the
 *   single-cell copy (T29): a date cell reading `01-15-24` copies that, not the
 *   serial `45306` underneath it.
 *
 * ## Quoting
 *
 * A cell's text can itself contain a tab, a newline or a quote — a shared string
 * with an embedded newline is common enough that the `uzbek-text` fixture has
 * several. Left alone, one such cell silently rewrites the whole paste into the
 * wrong shape. Those cells are wrapped in quotes with internal quotes doubled,
 * which is the convention every spreadsheet reads back correctly, and everything
 * else is emitted bare so the common case stays clean.
 */
internal fun SheetSnapshot.tsvOf(range: CellRange, formatted: FormattedValueCache): String {
    val out = StringBuilder()
    for (row in range.startRow..range.endRow) {
        if (row != range.startRow) out.append('\n')
        val cells = data.row(row)
        for (col in range.startCol..range.endCol) {
            if (col != range.startCol) out.append('\t')
            val value = cells?.valueAt(col) ?: continue
            out.append(escapeForTsv(formatted.format(value, cells.styleIdAt(col) ?: 0)))
        }
    }
    return out.toString()
}

/** A field a spreadsheet will read back as this exact text. */
private fun escapeForTsv(text: String): String =
    if (text.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
