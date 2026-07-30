package com.tikoncha.darcha.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Numeric rendering for the number formats Darcha implements exactly
 * (TECH_SPEC §7/§8).
 *
 * All of it is deliberately **locale-independent**: `.` is the decimal
 * separator and `,` the thousands separator, exactly as the OOXML format codes
 * write them. A viewer that showed `1.234,56` for a file whose format code says
 * `#,##0.00` would be showing something the author never wrote.
 *
 * Rounding is half-away-from-zero throughout, which is what Excel does.
 */
internal object NumericFormat {

    /** Significant digits kept by `General` before it gives up on plain digits. */
    private const val GENERAL_SIGNIFICANT_DIGITS = 11

    /** At or above this magnitude `General` switches to scientific notation. */
    private val GENERAL_UPPER: BigDecimal = BigDecimal("1E+11")

    /** Below this magnitude (and non-zero) `General` switches to scientific. */
    private val GENERAL_LOWER: BigDecimal = BigDecimal("1E-4")

    private val GENERAL_CONTEXT = MathContext(GENERAL_SIGNIFICANT_DIGITS, RoundingMode.HALF_UP)

    private const val GROUP_SIZE = 3

    /**
     * The `General` format: as many digits as the value needs, up to
     * [GENERAL_SIGNIFICANT_DIGITS] significant ones, with trailing zeros
     * trimmed, falling back to scientific notation outside
     * `[1E-4, 1E+11)`.
     *
     * Excel's own `General` is column-width dependent — it shows fewer digits in
     * a narrow column. Darcha's is not: a value must not change its text because
     * the user dragged a column. The digit budget above is Excel's at its default
     * width.
     */
    fun general(value: Double): String {
        if (!value.isFinite()) return NOT_A_NUMBER
        if (value == 0.0) return "0" // also catches -0.0
        val rounded = BigDecimal.valueOf(value).round(GENERAL_CONTEXT).stripTrailingZeros()
        val magnitude = rounded.abs()
        return if (magnitude >= GENERAL_UPPER || magnitude < GENERAL_LOWER) {
            scientific(rounded)
        } else {
            rounded.toPlainString()
        }
    }

    /**
     * A fixed number of decimals, optionally grouped in thousands and/or scaled
     * to a percentage — the shape shared by the builtin codes `0`, `0.00`,
     * `#,##0`, `#,##0.00`, `0%` and `0.00%`.
     */
    fun fixed(value: Double, decimals: Int, grouping: Boolean, percent: Boolean): String {
        if (!value.isFinite()) return NOT_A_NUMBER

        var amount = BigDecimal.valueOf(value)
        // Exact decimal shift, so 0.075 as a percentage is 7.5 and not 7.4999…
        if (percent) amount = amount.movePointRight(2)
        amount = amount.setScale(decimals, RoundingMode.HALF_UP)

        val digits = amount.abs().toPlainString()
        val body = if (grouping) group(digits) else digits
        // A value that rounds to zero is shown as zero, not as "-0.00".
        val sign = if (amount.signum() < 0) "-" else ""
        return sign + body + if (percent) "%" else ""
    }

    /** Scientific notation with Excel's two-digit exponent, e.g. `1.2345E-05`. */
    private fun scientific(rounded: BigDecimal): String {
        // For a stripped BigDecimal, precision - scale - 1 is the base-10
        // exponent of its leading digit.
        val exponent = rounded.precision() - rounded.scale() - 1
        val mantissa = rounded.movePointLeft(exponent).stripTrailingZeros()
        val sign = if (exponent < 0) "-" else "+"
        val magnitude = kotlin.math.abs(exponent).toString().padStart(2, '0')
        return "${mantissa.toPlainString()}E$sign$magnitude"
    }

    /** Insert thousands separators into the integer part of [plain]. */
    private fun group(plain: String): String {
        val dot = plain.indexOf('.')
        val integerPart = if (dot < 0) plain else plain.substring(0, dot)
        val rest = if (dot < 0) "" else plain.substring(dot)
        if (integerPart.length <= GROUP_SIZE) return plain

        val out = StringBuilder(integerPart.length + integerPart.length / GROUP_SIZE + rest.length)
        val lead = integerPart.length % GROUP_SIZE
        if (lead > 0) out.append(integerPart, 0, lead)
        var i = lead
        while (i < integerPart.length) {
            if (out.isNotEmpty()) out.append(',')
            out.append(integerPart, i, i + GROUP_SIZE)
            i += GROUP_SIZE
        }
        return out.append(rest).toString()
    }

    /** What Excel shows for a value it cannot render as a number. */
    private const val NOT_A_NUMBER = "#NUM!"
}
