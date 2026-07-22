package com.tikoncha.darcha.parser

/**
 * The OOXML built-in number formats (TECH_SPEC §7 step 4).
 *
 * Built-in formats are not stored in the file — the spec assumes them. Only the
 * ids the spec actually defines are listed here; reserved / locale-specific gaps
 * (5–8, 23–36, 41–44, 50–163) are absent and resolve to `null`.
 */
internal object BuiltinNumberFormats {

    private val CODES: Map<Int, String> = mapOf(
        0 to "General",
        1 to "0",
        2 to "0.00",
        3 to "#,##0",
        4 to "#,##0.00",
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        12 to "# ?/?",
        13 to "# ??/??",
        14 to "mm-dd-yy",
        15 to "d-mmm-yy",
        16 to "d-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        22 to "m/d/yy h:mm",
        37 to "#,##0 ;(#,##0)",
        38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)",
        40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mmss.0",
        48 to "##0.0E+0",
        49 to "@",
    )

    /** The built-in format code for [id], or `null` if the id is undefined. */
    fun codeFor(id: Int): String? = CODES[id]
}
