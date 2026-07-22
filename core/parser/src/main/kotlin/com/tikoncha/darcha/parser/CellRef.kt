package com.tikoncha.darcha.parser

import com.tikoncha.darcha.model.CellRange

/**
 * Conversions for A1-style cell references (e.g. `AA100`) to 0-based
 * coordinates (TECH_SPEC §7 step 5).
 */
internal object CellRef {

    /**
     * The 0-based column index of an A1 reference. The letters form a bijective
     * base-26 number: `A`→0, `Z`→25, `AA`→26, `AAA`→702, `XFD`→16383. Digits
     * (the row part) terminate the scan.
     */
    fun columnIndexOf(ref: String): Int {
        var column = 0
        for (c in ref) {
            val digit = when (c) {
                in 'A'..'Z' -> c - 'A' + 1
                in 'a'..'z' -> c - 'a' + 1
                else -> break
            }
            column = column * 26 + digit
        }
        return column - 1
    }

    /**
     * The 0-based row index of an A1 reference: `A1`→0, `AA100`→99. Returns 0 if
     * no row digits are present.
     */
    fun rowIndexOf(ref: String): Int {
        var i = 0
        while (i < ref.length && !ref[i].isDigit()) i++
        return (ref.substring(i).toIntOrNull() ?: 1) - 1
    }

    /**
     * Parse an A1-style range like `A1:C3` into a normalized [CellRange] (start
     * ≤ end on both axes). A single reference like `A1` yields a 1×1 range.
     */
    fun parseRange(ref: String): CellRange {
        val colon = ref.indexOf(':')
        if (colon < 0) {
            val row = rowIndexOf(ref)
            val col = columnIndexOf(ref)
            return CellRange(row, col, row, col)
        }
        val start = ref.substring(0, colon)
        val end = ref.substring(colon + 1)
        val r1 = rowIndexOf(start)
        val c1 = columnIndexOf(start)
        val r2 = rowIndexOf(end)
        val c2 = columnIndexOf(end)
        return CellRange(
            startRow = minOf(r1, r2),
            startCol = minOf(c1, c2),
            endRow = maxOf(r1, r2),
            endCol = maxOf(c1, c2),
        )
    }
}
