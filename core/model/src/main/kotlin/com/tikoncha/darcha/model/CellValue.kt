package com.tikoncha.darcha.model

/**
 * The raw, unformatted value of a cell (TECH_SPEC §8). A cell stores its value
 * plus a style id; the display string is computed lazily at render time (T16),
 * not here.
 */
public sealed interface CellValue {

    /** A numeric value. Dates are numbers too — date-ness comes from the style. */
    public data class Number(public val value: Double) : CellValue

    /** A reference into the workbook's shared string table by [index]. */
    public data class SharedText(public val index: Int) : CellValue

    /** A string stored inline in the cell (`inlineStr`, or a `str` formula result). */
    public data class InlineText(public val text: String) : CellValue

    /** A boolean value. */
    public data class Bool(public val value: Boolean) : CellValue

    /** An error value, holding the error [code] (e.g. `#DIV/0!`). */
    public data class Error(public val code: String) : CellValue
}
