package com.tikoncha.darcha.model

/**
 * The resolved visual + format style of a cell — one entry of `cellXfs`
 * (TECH_SPEC §7 step 4). A cell's `s` attribute indexes into a [StyleTable].
 *
 * @property bold whether the font is bold.
 * @property italic whether the font is italic.
 * @property fontColor resolved font color, or `null` for default/automatic.
 * @property fillColor resolved solid fill color, or `null` for no fill.
 * @property horizontalAlignment horizontal text alignment.
 * @property verticalAlignment vertical text alignment.
 * @property numFmtId the number-format id (builtin 0–163, or custom ≥ 164).
 * @property formatCode the resolved format code, or `null` for undefined ids.
 * @property isDate whether [numFmtId]/[formatCode] denotes a date or time.
 */
public data class CellStyle(
    public val bold: Boolean,
    public val italic: Boolean,
    public val fontColor: Color?,
    public val fillColor: Color?,
    public val horizontalAlignment: HorizontalAlignment,
    public val verticalAlignment: VerticalAlignment,
    public val numFmtId: Int,
    public val formatCode: String?,
    public val isDate: Boolean,
) {
    public companion object {
        /** The style of an unstyled cell (General number format, no formatting). */
        public val DEFAULT: CellStyle = CellStyle(
            bold = false,
            italic = false,
            fontColor = null,
            fillColor = null,
            horizontalAlignment = HorizontalAlignment.GENERAL,
            verticalAlignment = VerticalAlignment.BOTTOM,
            numFmtId = 0,
            formatCode = null,
            isDate = false,
        )
    }
}

/**
 * The workbook's resolved cell-style table (`cellXfs`). A cell's `s` attribute
 * indexes into [styles]; callers fall back to [CellStyle.DEFAULT] for absent or
 * out-of-range indices.
 *
 * @property styles the styles in `cellXfs` order.
 */
public data class StyleTable(
    public val styles: List<CellStyle>,
) {
    /** The style at [index], or `null` if [index] is out of range. */
    public operator fun get(index: Int): CellStyle? = styles.getOrNull(index)

    /** Number of styles in the table. */
    public val size: Int get() = styles.size

    public companion object {
        /** An empty table — used when the workbook has no `styles.xml`. */
        public val EMPTY: StyleTable = StyleTable(emptyList())
    }
}
