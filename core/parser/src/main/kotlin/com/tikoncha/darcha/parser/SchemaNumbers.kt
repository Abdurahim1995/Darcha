package com.tikoncha.darcha.parser

import kotlin.math.floor

/**
 * Read a value that ECMA-376 types as `xsd:double` but that means a **count**
 * (T30).
 *
 * ## Why this exists
 *
 * The schema does not type OOXML attributes by what they mean; it types them by
 * what they can hold. `CT_Pane/@xSplit` and `@ySplit` are declared `xsd:double`
 * because in an *unfrozen* split pane the split is a position and can land
 * between rows. In a **frozen** pane the same attribute is a count of frozen
 * rows or columns — a whole number in every file anyone will ever open.
 *
 * So `"2"` is what almost every producer writes, and reading it with
 * `toIntOrNull()` works right up until a producer writes the other legal
 * spelling. Google Sheets writes `ySplit="2.0"`. `"2.0".toIntOrNull()` returns
 * `null`, the parser fell back to `0`, and **every frozen pane in every Google
 * Sheets export was silently lost** — no error, no warning, just a sheet that
 * scrolled when it should not have. That is the bug this function exists to make
 * unrepeatable.
 *
 * ## The rule for the rest of the parser
 *
 * The audit that followed found the pattern the schema actually uses:
 *
 * - **Ids and indices** — `numFmtId`, `fontId`, `fillId`, `sheetId`, a cell's
 *   `s`, a row's `r`, `<col>`'s `min`/`max`, a colour's `indexed`/`theme` — are
 *   `xsd:unsignedInt`. Read them with `toIntOrNull()`; that is correct.
 * - **Measurements** — `width`, `ht`, `defaultColWidth`, `defaultRowHeight` —
 *   are `xsd:double` and are already read as doubles.
 * - **Counts the schema still types as double** — only the two pane splits.
 *   They belong here.
 *
 * If a future attribute needs this, it goes through this function rather than
 * growing its own `toDoubleOrNull()?.toInt()`, so the bug cannot come back one
 * attribute at a time.
 *
 * ## What a fractional value means
 *
 * Nothing, for a frozen pane — you cannot freeze half a row. [asWholeCount]
 * therefore takes the **floor**: `"2.5"` freezes 2. Rounding up would freeze a
 * row the author never asked to freeze, and the conservative reading keeps every
 * row that was meant to scroll scrolling. A genuinely fractional split only
 * occurs on `state="split"`, which this parser does not treat as frozen at all.
 *
 * Negative values clamp to zero: a negative count has no meaning, and the
 * alternative is a negative frozen-row count reaching the geometry engine.
 */
internal fun String?.asWholeCount(default: Int = 0): Int {
    val value = this?.trim()?.toDoubleOrNull() ?: return default
    if (value.isNaN()) return default
    val floored = floor(value)
    return when {
        floored <= 0.0 -> 0
        floored >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
        else -> floored.toInt()
    }
}
