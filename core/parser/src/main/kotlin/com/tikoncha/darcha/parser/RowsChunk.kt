package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.Row

/**
 * A batch of worksheet rows delivered during progressive loading (TECH_SPEC §7).
 *
 * Each chunk holds the rows parsed since the previous chunk (a delta), keyed by
 * 0-based row index. Concatenating every chunk's [rows] reproduces the full
 * sheet, which lets the UI render rows as they stream in.
 *
 * @property rows the rows parsed in this chunk.
 * @property rowsSoFar cumulative count of populated rows emitted so far.
 */
public data class RowsChunk(
    public val rows: Map<Int, Row>,
    public val rowsSoFar: Int,
)
