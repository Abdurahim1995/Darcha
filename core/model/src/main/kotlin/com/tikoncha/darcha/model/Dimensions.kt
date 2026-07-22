package com.tikoncha.darcha.model

/**
 * The maximum digit width (in pixels) of the default font, used to convert
 * column widths. For Calibri 11 at 96 DPI this is 7 px.
 */
public const val DEFAULT_MAX_DIGIT_WIDTH: Int = 7

/**
 * Convert an OOXML column width (in "character units" of the workbook's default
 * font) to whole pixels — the single, central conversion (TECH_SPEC §7 traps).
 *
 * Per ECMA-376 §18.3.1.13, the stored width counts characters of the font's
 * maximum digit width ([maxDigitWidth], MDW) and bakes in 5 px of cell padding.
 * This reverses that formula:
 *
 * ```
 * pixels = trunc( ( (256 * width + trunc(128 / MDW)) / 256 ) * MDW )
 * ```
 *
 * @param width the column width in character units (e.g. `col/@width`).
 * @param maxDigitWidth the default font's maximum digit width, in pixels.
 * @return the column width in whole pixels.
 */
public fun columnWidthToPixels(width: Double, maxDigitWidth: Int = DEFAULT_MAX_DIGIT_WIDTH): Int =
    (((256.0 * width + 128 / maxDigitWidth) / 256.0) * maxDigitWidth).toInt()
