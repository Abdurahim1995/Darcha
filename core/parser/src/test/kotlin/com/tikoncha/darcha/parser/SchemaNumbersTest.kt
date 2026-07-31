package com.tikoncha.darcha.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The schema-double-as-count rule (T30).
 *
 * Every case here is a spelling ECMA-376 permits for an attribute it types
 * `xsd:double` but that means a count. The one that started this — `"2.0"`, as
 * written by Google Sheets — used to parse as `0` and silently drop a sheet's
 * frozen panes.
 */
class SchemaNumbersTest {

    // --- the two spellings that both mean "two" ---

    @Test
    fun anIntegerSpelling_isTheCount() {
        assertEquals(2, "2".asWholeCount())
    }

    @Test
    fun aDecimalSpelling_isTheSameCount() {
        // Google Sheets writes this. Excel writes "2". Same file, same meaning.
        assertEquals(2, "2.0".asWholeCount())
    }

    @Test
    fun otherLegalDoubleSpellings_alsoWork() {
        assertEquals(2, "2.00".asWholeCount())
        assertEquals(2, "+2".asWholeCount())
        assertEquals(200, "2e2".asWholeCount())
        assertEquals(2, " 2.0 ".asWholeCount())
    }

    // --- what a fraction means, decided rather than stumbled into ---

    /**
     * You cannot freeze half a row, so the floor is taken: `"2.5"` freezes two.
     *
     * Rounding to the nearest would freeze a third row the author never asked
     * for, and freezing too much is the worse error — a row that should scroll
     * and does not looks like a broken viewer, while one fewer frozen row just
     * looks like the sheet. A genuinely fractional split only appears on
     * `state="split"`, which this parser does not treat as frozen at all.
     */
    @Test
    fun aFractionalCount_takesTheWholeRowsBelowIt() {
        assertEquals(2, "2.5".asWholeCount())
        assertEquals(2, "2.9999".asWholeCount())
        assertEquals(0, "0.5".asWholeCount())
    }

    // --- absent and malformed ---

    @Test
    fun anAbsentAttribute_isTheDefault() {
        // `frozen.xlsx` from Excel has no ySplit at all: one axis frozen, the
        // other not. That must read as zero, not as a failure.
        assertEquals(0, null.asWholeCount())
        assertEquals(7, null.asWholeCount(default = 7))
    }

    @Test
    fun anEmptyAttribute_isTheDefault() {
        assertEquals(0, "".asWholeCount())
        assertEquals(0, "   ".asWholeCount())
        assertEquals(7, "".asWholeCount(default = 7))
    }

    @Test
    fun garbage_isTheDefault_notACrash() {
        // The parser never throws across its module boundary, so unparseable
        // input falls back rather than propagating.
        for (junk in listOf("abc", "2,0", "--2", "1/2", "٢", "NaN")) {
            assertEquals("'$junk' should fall back", 0, junk.asWholeCount())
        }
    }

    // --- values that must not reach the geometry engine ---

    @Test
    fun negativeCounts_clampToZero() {
        // A negative frozen-row count would index backwards through the grid.
        assertEquals(0, "-1".asWholeCount())
        assertEquals(0, "-2.5".asWholeCount())
        assertEquals(0, "-0.0".asWholeCount())
    }

    @Test
    fun absurdlyLargeCounts_saturateInsteadOfOverflowing() {
        // Converting 1e30 to Int wraps to a negative number in JVM semantics.
        // Saturating keeps it positive; PaneRegions then clamps it to the sheet.
        assertEquals(Int.MAX_VALUE, "1e30".asWholeCount())
        assertEquals(Int.MAX_VALUE, "99999999999".asWholeCount())
    }

    @Test
    fun infinityAndNaN_doNotBecomeCounts() {
        assertEquals(Int.MAX_VALUE, "Infinity".asWholeCount())
        assertEquals(0, "-Infinity".asWholeCount())
        // "NaN" parses as a Double but means nothing as a count.
        assertEquals(0, "NaN".asWholeCount())
    }
}
