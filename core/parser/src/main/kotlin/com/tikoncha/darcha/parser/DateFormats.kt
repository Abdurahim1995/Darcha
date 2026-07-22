package com.tikoncha.darcha.parser

/**
 * Date/time detection for number formats (TECH_SPEC §7 step 4).
 */
internal object DateFormats {

    private const val DATE_TOKENS = "ymdhs"

    /**
     * Whether a cell with number format [numFmtId] / [formatCode] renders as a
     * date or time.
     *
     * Built-in ids 14–22 and 45–47 are date/time formats. For any other format,
     * [formatCode] is a date format if — after removing quoted literals, escaped
     * characters, spacing/fill directives, and `[...]` (color / condition /
     * locale / elapsed) sections — it still contains a `y`, `m`, `d`, `h`, or
     * `s` token.
     */
    fun isDateFormat(numFmtId: Int, formatCode: String?): Boolean {
        if (numFmtId in 14..22 || numFmtId in 45..47) return true
        val code = formatCode ?: return false
        if (code.isEmpty() || code.equals("General", ignoreCase = true) || code == "@") return false
        return stripNonDate(code).any { it.lowercaseChar() in DATE_TOKENS }
    }

    /** Remove quoted text, escaped chars, `_`/`*` directives, and `[...]` sections. */
    private fun stripNonDate(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            when (val c = code[i]) {
                '"' -> {
                    i++
                    while (i < code.length && code[i] != '"') i++
                    i++ // closing quote
                }
                '\\' -> i += 2 // escaped literal character
                '_', '*' -> i += 2 // spacing / fill directive + its argument
                '[' -> {
                    i++
                    while (i < code.length && code[i] != ']') i++
                    i++ // closing bracket
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
