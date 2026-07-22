package com.tikoncha.darcha.parser

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
}
