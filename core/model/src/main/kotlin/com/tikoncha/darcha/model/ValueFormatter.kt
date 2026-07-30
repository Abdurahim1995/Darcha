package com.tikoncha.darcha.model

/**
 * Turns a raw [CellValue] into the text a spreadsheet would show for it
 * (TECH_SPEC §7/§8).
 *
 * The model stores values raw — a date is a number, a shared string is an index
 * — so this is where a cell finally becomes a string. It is a pure function of
 * `(value, style, strings, date1904)`: no locale, no time zone, no ambient
 * state, and therefore fully testable on a plain JVM.
 *
 * ## What is implemented
 *
 * | Format | Handling |
 * |---|---|
 * | `General` (id 0) and anything unlisted | [NumericFormat.general] |
 * | `0` `0.00` `#,##0` `#,##0.00` (1–4) | fixed decimals, optional grouping |
 * | `0%` `0.00%` (9, 10) | scaled by 100, with a `%` |
 * | dates and times (14–22, 45–47) | [DateTimeFormat], through their codes |
 * | custom codes (id ≥ 164) | [DateTimeFormat] when [CellStyle.isDate], else `General` |
 *
 * The remaining builtins — fractions (12, 13), scientific (11, 48), the
 * accounting variants (37–40) and text (49) — render as `General`. They are rare
 * in practice, and a wrong-but-confident rendering would be worse than a plain
 * number.
 *
 * ## Dates
 *
 * Date-ness comes from the style, never from the value: [CellStyle.isDate] is
 * resolved once by the parser. A **negative** serial falls back to a plain
 * number, because there is no such date — Excel gives up too and fills the cell
 * with `#####`, which tells the reader nothing.
 *
 * See [ExcelSerial] for the epochs and the 1900 leap-year bug.
 */
public object ValueFormatter {

    /**
     * The display text for [value].
     *
     * @param value the cell's raw value.
     * @param style the cell's resolved style; supplies the number format.
     * @param strings the workbook's shared strings, for
     *   [CellValue.SharedText]. An index with no entry renders as empty text.
     * @param date1904 the workbook's epoch flag.
     */
    public fun format(
        value: CellValue,
        style: CellStyle = CellStyle.DEFAULT,
        strings: StringTable = StringTable.EMPTY,
        date1904: Boolean = false,
    ): String = when (value) {
        is CellValue.Number -> number(value.value, style, date1904)
        is CellValue.SharedText -> strings[value.index].orEmpty()
        is CellValue.InlineText -> value.text
        is CellValue.Bool -> if (value.value) "TRUE" else "FALSE"
        is CellValue.Error -> value.code
    }

    private fun number(value: Double, style: CellStyle, date1904: Boolean): String {
        if (style.isDate && value >= 0.0) {
            val code = style.formatCode
            // A date style whose code never reached us (an id the spec leaves
            // undefined) falls through to a number rather than guessing.
            if (code != null) DateTimeFormat.render(value, code, date1904)?.let { return it }
        }
        return when (style.numFmtId) {
            ID_INTEGER -> NumericFormat.fixed(value, 0, grouping = false, percent = false)
            ID_TWO_DECIMALS -> NumericFormat.fixed(value, 2, grouping = false, percent = false)
            ID_GROUPED -> NumericFormat.fixed(value, 0, grouping = true, percent = false)
            ID_GROUPED_TWO_DECIMALS -> NumericFormat.fixed(value, 2, grouping = true, percent = false)
            ID_PERCENT -> NumericFormat.fixed(value, 0, grouping = false, percent = true)
            ID_PERCENT_TWO_DECIMALS -> NumericFormat.fixed(value, 2, grouping = false, percent = true)
            else -> NumericFormat.general(value)
        }
    }

    // The builtin ids implemented exactly. Their codes are in the OOXML spec and
    // never appear in the file itself.
    private const val ID_INTEGER = 1 // "0"
    private const val ID_TWO_DECIMALS = 2 // "0.00"
    private const val ID_GROUPED = 3 // "#,##0"
    private const val ID_GROUPED_TWO_DECIMALS = 4 // "#,##0.00"
    private const val ID_PERCENT = 9 // "0%"
    private const val ID_PERCENT_TWO_DECIMALS = 10 // "0.00%"
}
